package com.ficmart.gateway.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;

/**
 * Owns the two-phase atomic transaction pattern for all payment operations.
 *
 * <p>Each operation is split into two separate {@link Transactional} methods with the
 * bank call happening between them in {@link PaymentService}. This ensures no database
 * connection is held open across the network call to the bank.
 *
 * <p>Phase 1 commits intent to the database before touching the bank.
 * Phase 2 commits the bank's response after it returns.
 * If the gateway crashes between phases, the {@link com.ficmart.gateway.idempotency.ReconciliationWorker}
 * detects the intermediate status and replays the bank call idempotently.
 *
 * <p>Capture, void, and refund phase 1 methods use {@code SELECT FOR UPDATE} via
 * {@link PaymentRepository#findByIdForUpdate} to prevent competing operations
 * (e.g. simultaneous capture and void) from both proceeding on the same payment.
 */
@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentTransactionService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    /**
     * Phase 1 of authorize. Creates the payment row in {@code PENDING} status and writes
     * the {@code AUTHORIZATION_REQUESTED} event. No bank call has been made yet.
     *
     * <p>Note: no {@code SELECT FOR UPDATE} here — the payment row does not exist yet.
     */
    @Transactional
    public Payment authorizePhaseOne(AuthorizeRequest request, UUID idempotencyKey) {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();

        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setOrderId(request.orderId());
        payment.setCustomerId(request.customerId());
        payment.setAmountCents(request.amountCents());
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        paymentRepository.save(payment);

        saveEvent(paymentId, idempotencyKey, PaymentEventType.AUTHORIZATION_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of authorize on bank success. Transitions payment to {@code AUTHORIZED},
     * stores the bank authorization ID and expiry, and writes the {@code AUTHORIZATION_SUCCEEDED} event.
     */
    @Transactional
    public Payment authorizePhaseTwoSuccess(Payment payment, BankAuthorizationResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setBankAuthId(bankResponse.authorizationId());
        payment.setExpiresAt(bankResponse.expiresAt());
        payment.setAuthorizedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_SUCCEEDED);

        return payment;
    }

    /**
     * Phase 2 of authorize on permanent bank failure. Transitions payment to {@code FAILED}
     * and writes the {@code AUTHORIZATION_FAILED} event.
     */
    @Transactional
    public void authorizePhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_FAILED);
    }

    /**
     * Phase 1 of capture. Loads the payment with a pessimistic lock ({@code SELECT FOR UPDATE}),
     * validates the state machine transition to {@code CAPTURING}, and writes the
     * {@code CAPTURE_REQUESTED} event. The lock is released when this transaction commits.
     *
     * <p>The {@code CAPTURING} intermediate status serves two purposes:
     * blocks competing void operations and signals the reconciliation worker on crash recovery.
     */
    @Transactional
    public Payment capturePhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.CAPTURING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of capture on bank success. Transitions payment to {@code CAPTURED},
     * stores the bank capture ID and timestamp, and writes the {@code CAPTURE_SUCCEEDED} event.
     */
    @Transactional
    public Payment capturePhaseTwoSuccess(Payment payment, BankCaptureResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setBankCaptureId(bankResponse.captureId());
        payment.setCaptureAt(bankResponse.capturedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_SUCCEEDED);
    
        return payment;
    }
    
    /**
     * Phase 2 of capture on permanent bank failure. Transitions payment to {@code FAILED}
     * and writes the {@code CAPTURE_FAILED} event.
     */
    @Transactional
    public void capturePhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_FAILED);
    }

    /**
     * Phase 2 of void on bank success. Transitions payment to {@code VOIDED},
     * stores the bank void ID and timestamp, and writes the {@code VOID_SUCCEEDED} event.
     */
    @Transactional
    public Payment voidPhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));
        
        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.VOIDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of void on bank success. Transitions payment to {@code VOIDED},
     * stores the bank void ID and timestamp, and writes the {@code VOID_SUCCEEDED} event.
     */
    @Transactional
    public Payment voidPhaseTwoSuccess(Payment payment, BankVoidResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.VOIDED);
        payment.setBankVoidId(bankResponse.voidId());
        payment.setVoidedAt(bankResponse.voidedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_SUCCEEDED);
    
        return payment;
    }

    /**
     * Phase 2 of void on permanent bank failure. Transitions payment to {@code FAILED}
     * and writes the {@code VOID_FAILED} event.
     */
    @Transactional
    public void voidPhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_FAILED);
    }

    /**
     * Phase 1 of refund. Loads the payment with a pessimistic lock ({@code SELECT FOR UPDATE}),
     * validates the state machine transition to {@code REFUNDING}, and writes the
     * {@code REFUND_REQUESTED} event. The lock is released when this transaction commits.
     *
     * <p>The {@code REFUNDING} intermediate status blocks competing operations
     * and signals the reconciliation worker on crash recovery.
     */
    @Transactional
    public Payment refundPhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));
        
        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.REFUNDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of refund on bank success. Transitions payment to {@code REFUNDED},
     * stores the bank refund ID and timestamp, and writes the {@code REFUND_SUCCEEDED} event.
     */
    @Transactional
    public Payment refundPhaseTwoSuccess(Payment payment, BankRefundResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setBankRefundId(bankResponse.refundId());
        payment.setRefundedAt(bankResponse.refundedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_SUCCEEDED);
    
        return payment;
    }

    /**
     * Phase 2 of refund on permanent bank failure. Transitions payment to {@code FAILED}
     * and writes the {@code REFUND_FAILED} event.
     */
    @Transactional
    public void refundPhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_FAILED);
    }

    /**
     * Writes a {@link PaymentEvent} row for the given payment. Called in every phase
     * of every operation — extracted to avoid duplication across 12 phase methods.
     */
    private void saveEvent(UUID paymentId, UUID idempotencyKey, PaymentEventType eventType) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(paymentId);
        event.setIdempotencyKey(idempotencyKey);
        event.setEventType(eventType);
        event.setCreatedAt(Instant.now());
        paymentEventRepository.save(event);
    }
}