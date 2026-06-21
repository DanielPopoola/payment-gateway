package com.ficmart.gateway.payment;

import java.util.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.idempotency.*;

/**
 * Orchestrates the full lifecycle of payment operations.
 *
 * <p>This service contains no database logic — it delegates persistence to
 * {@link PaymentTransactionService} and idempotency management to {@link IdempotencyKeyService}.
 * Its sole responsibility is sequencing: check idempotency → phase 1 → bank call → phase 2.
 *
 * <p>The two-transaction design is enforced here: {@code transactionService.phaseOne(...)} commits
 * before the bank call, and {@code transactionService.phaseTwoSuccess/Failure(...)} commits after.
 * {@link org.springframework.transaction.annotation.Transactional} is intentionally absent from
 * this class — wrapping everything in one transaction would hold the DB connection open across
 * the bank HTTP call.
 */
@Service
public class PaymentService {

    private final PaymentTransactionService transactionService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final BankClient bankClient;
    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentService(PaymentTransactionService transactionService,
            IdempotencyKeyService idempotencyKeyService,
            BankClient bankClient,
            ObjectMapper objectMapper,
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository) {
        this.transactionService = transactionService;
        this.idempotencyKeyService = idempotencyKeyService;
        this.bankClient = bankClient;
        this.objectMapper = objectMapper;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

     /**
     * Authorizes a payment by reserving funds on the provided card.
     *
     * <p>Creates a new {@link Payment} row. Unlike capture/void/refund, authorize does not
     * use {@code SELECT FOR UPDATE} because the payment row does not exist yet.
     *
     * @param request the card details and amount from FicMart
     * @param idempotencyKey client-provided UUID for deduplication
     * @return the authorized payment receipt
     */
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

    /**
     * Captures a previously authorized payment, charging the reserved funds.
     *
     * <p>Loads the payment first (without lock) to retrieve {@code customerId} for the
     * idempotency check, then re-loads with {@code SELECT FOR UPDATE} inside phase 1
     * to block concurrent void operations.
     */
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

    /**
     * Voids a previously authorized payment, releasing the reserved funds.
     * Cannot be called after capture.
     */
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


    /**
     * Refunds a captured payment, returning funds to the customer.
     * Cannot be called before capture.
     */
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

    public Payment getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        return payment;
    }

    public List<Payment> getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);   
    }

    public List<Payment> getPaymentByCustomerId(Long customerId) {
        return paymentRepository.findByCustomerId(customerId, PageRequest.of(0, 20));
    }

    public List<PaymentEvent> getPaymentEvents(UUID paymenetId) {
        return paymentEventRepository.findByPaymentIdOrderByCreatedAtAsc(paymenetId);
    }

    /**
     * Computes a SHA-256 hash of the request fields, used to detect idempotency key reuse
     * with a different payload (client bug).
     *
     * @param fields the request fields to hash, joined with "|" separator
     * @return Base64-encoded SHA-256 digest
     */
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