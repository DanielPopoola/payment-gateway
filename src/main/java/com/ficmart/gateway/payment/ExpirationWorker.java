package com.ficmart.gateway.payment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ficmart.gateway.bank.BankAuthorizationResponse;
import com.ficmart.gateway.bank.BankClient;
import com.ficmart.gateway.common.GatewayException;


import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExpirationWorker {

    private final PaymentRepository paymentRepository;
    private final BankClient bankClient;
    private final PaymentTransactionService transactionService;

    @Value("${workers.expiration.buffer-days:0}")
    private int bufferDays;

    public ExpirationWorker(PaymentRepository paymentRepository,
            BankClient bankClient,
            PaymentTransactionService transactionService) {
        this.paymentRepository = paymentRepository;
        this.bankClient = bankClient;
        this.transactionService = transactionService;
    }

    @Scheduled(fixedDelayString = "${workers.expiration.delay-ms:3600000}")
    public void run() {
        List<Payment> candidates = paymentRepository.findByStatusAndExpiresAtBefore(
            PaymentStatus.AUTHORIZED,
            Instant.now().minus(bufferDays, ChronoUnit.DAYS));

        for (Payment payment : candidates) {
            try {
                checkAndExpire(payment);
            } catch (Exception ex) {
                log.error("Unexpected error processing expiration for payment {}: {}",
                    payment.getId(), ex.getMessage());
            }
        }
    }

    private void checkAndExpire(Payment payment) {
        try {
            BankAuthorizationResponse bankResponse = bankClient.getAuthorization(payment.getBankAuthId());

            if ("expired".equals(bankResponse.status())) {
                transactionService.expirePayment(payment);
                log.info("Payment {} marked EXPIRED — bank confirmed expiry", payment.getId());
            } else {
                log.info("Payment {} expires_at passed but bank says valid — skipping (clock skew)",
                    payment.getId());
            }
        } catch (GatewayException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                transactionService.expirePayment(payment);
                log.info("Payment {} marked EXPIRED — authorization not found at bank", payment.getId());
            } else {
                log.error("Bank error checking expiration for payment {}: {}", payment.getId(), ex.getMessage());
            }
        }
    }
}