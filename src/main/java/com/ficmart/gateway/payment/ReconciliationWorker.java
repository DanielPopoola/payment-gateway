package com.ficmart.gateway.payment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.idempotency.IdempotencyRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReconciliationWorker {

    private final PaymentRepository paymentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final BankClient bankClient;
    private final PaymentTransactionService transactionService;
    private final AtomicInteger stuckAuthorizationCount = new AtomicInteger(0);

    @Value("${workers.reconciliation.stuck-threshold-minutes:3}")
    private int stuckThresholdMinutes;

    public ReconciliationWorker(PaymentRepository paymentRepository,
            IdempotencyRepository idempotencyRepository,
            BankClient bankClient,
            PaymentTransactionService transactionService) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.bankClient = bankClient;
        this.transactionService = transactionService;
    }

    @Scheduled(fixedDelayString = "${workers.reconciliation.delay-ms:60000}")
    public void run() {
        recoverStuckIntermediates();
        alertStuckPending();
    }

    private void recoverStuckIntermediates() {
        List<Payment> stuckPayments = paymentRepository.findByStatusInAndUpdatedAtBefore(
            List.of(PaymentStatus.CAPTURING, PaymentStatus.VOIDING, PaymentStatus.REFUNDING),
            Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES));

        for (Payment payment : stuckPayments) {
            try {
                recover(payment);
            } catch (Exception ex) {
                log.error("Unexpected error recovering payment {}: {}", payment.getId(), ex.getMessage());
            }
        }
    }

    private void alertStuckPending() {
        List<Payment> pendingPayments = paymentRepository.findByStatusInAndUpdatedAtBefore(
            List.of(PaymentStatus.PENDING),
            Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES));

        for (Payment payment : pendingPayments) {
            try {
                long ageMinutes = ChronoUnit.MINUTES.between(payment.getCreatedAt(), Instant.now());
                log.warn("Stuck authorization detected: payment_id={} customer_id={} created_at={} age_minutes={}",
                    payment.getId(), payment.getCustomerId(), payment.getCreatedAt(), ageMinutes);
                stuckAuthorizationCount.incrementAndGet();
            } catch (Exception ex) {
                log.error("Unexpected error alerting stuck payment {}: {}", payment.getId(), ex.getMessage());
            }
        }
    }

    private void recover(Payment payment) {
        UUID idempotencyKey = idempotencyRepository.findByPaymentId(payment.getId()).getIdempotencyKey();

        switch (payment.getStatus()) {
            case CAPTURING -> {
                BankCaptureResponse bankResponse = bankClient.capture(
                    new BankCaptureRequest(payment.getAmountCents(), payment.getBankAuthId()),
                    idempotencyKey.toString());
                transactionService.capturePhaseTwoSuccess(payment, bankResponse, idempotencyKey);
            }
            case VOIDING -> {
                BankVoidResponse bankResponse = bankClient.void_(
                    new BankVoidRequest(payment.getBankAuthId()),
                    idempotencyKey.toString());
                transactionService.voidPhaseTwoSuccess(payment, bankResponse, idempotencyKey);
            }
            case REFUNDING -> {
                BankRefundResponse bankResponse = bankClient.refund(
                    new BankRefundRequest(payment.getAmountCents(), payment.getBankCaptureId()),
                    idempotencyKey.toString());
                transactionService.refundPhaseTwoSuccess(payment, bankResponse, idempotencyKey);
            }
            default -> log.warn("Unexpected status in reconciliation: {}", payment.getStatus());
        }
    }
}