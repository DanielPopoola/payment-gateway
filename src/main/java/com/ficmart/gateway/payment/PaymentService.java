package com.ficmart.gateway.payment;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.idempotency.*;


@Service
public class PaymentService {

    private final PaymentTransactionService transactionService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final BankClient bankClient;
    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentTransactionService transactionService,
            IdempotencyKeyService idempotencyKeyService,
            BankClient bankClient,
            ObjectMapper objectMapper,
            PaymentRepository paymentRepository) {
        this.transactionService = transactionService;
        this.idempotencyKeyService = idempotencyKeyService;
        this.bankClient = bankClient;
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
    }

    public Payment authorize(AuthorizeRequest request, UUID idempotencyKey) {
        String requestHash = hashRequest(request.cardNumber(), request.amountCents(), 
            request.orderId(), request.customerId());

        Payment replay = idempotencyKeyService.checkAndReplay(
            request.customerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment payment = transactionService.authorizePhaseOne(request, idempotencyKey);

        IdempotencyKey idempotencyKeyEntity = idempotencyKeyService.lock(
            request.customerId(), idempotencyKey, payment.getId(), requestHash);

        try {
            BankAuthorizationResponse bankResponse = bankClient.authorize(
                new BankAuthorizeRequest(request.amountCents(), request.cardNumber(),
                    request.cvv(), request.expiryMonth(), request.expiryYear()),
                idempotencyKey.toString());

            Payment result = transactionService.authorizePhaseTwoSuccess(
                payment, bankResponse, idempotencyKey);

            idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(result));
            return result;

        } catch (GatewayException ex) {
            transactionService.authorizePhaseTwoFailure(payment, ex, idempotencyKey);
            idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());
            throw ex;
        }
    }

    public Payment capture(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.capturePhaseOne(paymentId, idempotencyKey);

        IdempotencyKey idempotencyKeyEntity = idempotencyKeyService.lock(
            payment.getCustomerId(), idempotencyKey, paymentId, requestHash);

        try {
            BankCaptureResponse bankResponse = bankClient.capture(
                new BankCaptureRequest(lockedPayment.getAmountCents(), lockedPayment.getBankAuthId()),
                idempotencyKey.toString());
            
            Payment result = transactionService.capturePhaseTwoSuccess(lockedPayment, bankResponse, idempotencyKey);

            idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(result));
            return result;
        } catch (GatewayException ex) {
            transactionService.capturePhaseTwoFailure(lockedPayment, ex, idempotencyKey);
            idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());
            throw ex;
        }
    }

    public Payment void_(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.voidPhaseOne(paymentId, idempotencyKey);

        IdempotencyKey idempotencyKeyEntity = idempotencyKeyService.lock(
            payment.getCustomerId(), idempotencyKey, paymentId, requestHash);

        try {
            BankVoidResponse bankResponse = bankClient.void_(new BankVoidRequest(lockedPayment.getBankAuthId()),
                idempotencyKey.toString());
            
            Payment result = transactionService.voidPhaseTwoSuccess(lockedPayment, bankResponse, idempotencyKey);

            idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(result));
            return result;
        } catch (GatewayException ex) {
            transactionService.voidPhaseTwoFailure(lockedPayment, ex, idempotencyKey);
            idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());
            throw ex;
        }
    }

    public Payment refund(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.refundPhaseOne(paymentId, idempotencyKey);

        IdempotencyKey idempotencyKeyEntity = idempotencyKeyService.lock(
            payment.getCustomerId(), idempotencyKey, paymentId, requestHash);

        try {
            BankRefundResponse bankResponse = bankClient.refund(
                new BankRefundRequest(lockedPayment.getAmountCents(), lockedPayment.getBankCaptureId()),
                idempotencyKey.toString());
            
            Payment result = transactionService.refundPhaseTwoSuccess(lockedPayment, bankResponse, idempotencyKey);

            idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(result));
            return result;
        } catch (GatewayException ex) {
            transactionService.refundPhaseTwoFailure(lockedPayment, ex, idempotencyKey);
            idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());
            throw ex;
        }
    }

    private static String hashRequest(Object... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = Arrays.stream(fields).map(Object::toString).collect(Collectors.joining("|"));
            return Base64.getEncoder().encodeToString(digest.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize response", e);
        }
    }
}