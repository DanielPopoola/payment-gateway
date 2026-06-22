package com.ficmart.gateway.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.bank.BankAuthorizationResponse;
import com.ficmart.gateway.bank.BankCaptureResponse;
import com.ficmart.gateway.bank.BankRefundResponse;
import com.ficmart.gateway.bank.BankVoidResponse;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.idempotency.IdempotencyKey;
import com.ficmart.gateway.idempotency.IdempotencyKeyService;

/**
 * Owns the two-phase atomic transaction pattern for all four payment operations.
 *
 * <p>Each operation is split into two {@link Transactional} methods with the bank call
 * happening between them in {@link PaymentService}. This guarantees no database connection
 * is held open across the network call to the bank.
 *
 * <p><b>Phase 1</b> atomically commits three writes in a single transaction:
 * <ol>
 *   <li>Payment row — created (authorize) or updated to intermediate status (capture/void/refund)</li>
 *   <li>Idempotency key row — inserted with {@code locked_at} set, marking the operation in-flight</li>
 *   <li>Payment event — records the requested operation</li>
 * </ol>
 *
 * <p><b>Phase 2</b> atomically commits three writes in a single transaction:
 * <ol>
 *   <li>Payment row — updated to terminal or authorized status</li>
 *   <li>Idempotency key row — {@code locked_at} cleared, response stored for future replay</li>
 *   <li>Payment event — records success or failure</li>
 * </ol>
 *
 * <p>If the gateway crashes between phase 1 and phase 2, the payment row is left in an
 * intermediate status ({@code PENDING}, {@code CAPTURING}, {@code VOIDING}, {@code REFUNDING}).
 * The reconciliation worker detects this and replays the bank call idempotently to resolve it.
 *
 * <p>{@link IdempotencyKeyService#lock} and {@link IdempotencyKeyService#unlock} carry no
 * {@code @Transactional} annotation of their own — they participate in the calling phase
 * method's transaction via Spring's default {@code REQUIRED} propagation.
 */
@Service
public class PaymentTransactionService {
    
    private final ObjectMapper objectMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final IdempotencyKeyService idempotencyKeyService;
    

    public PaymentTransactionService(PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            IdempotencyKeyService idempotencyKeyService,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.idempotencyKeyService = idempotencyKeyService;
        this.objectMapper = objectMapper;
    }

    
    /**
     * Phase 1 of authorize. Atomically:
     * <ul>
     *   <li>Inserts the payment row in {@code PENDING} status</li>
     *   <li>Inserts the idempotency key row with {@code locked_at} set</li>
     *   <li>Writes the {@code AUTHORIZATION_REQUESTED} event</li>
     * </ul>
     *
     * <p>No {@code SELECT FOR UPDATE} here — the payment row does not exist yet,
     * so there is nothing to lock against competing operations.
     *
     * @param request the authorize request from FicMart
     * @param idempotencyKey client-provided UUID forwarded to the bank
     * @param requestHash SHA-256 of the request fields, stored for hash mismatch detection
     * @return the newly created payment in {@code PENDING} status
     */
    @Transactional
    public Payment authorizePhaseOne(AuthorizeRequest request, UUID idempotencyKey, String requestHash) {
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

        idempotencyKeyService.lock(idempotencyKey, paymentId, requestHash);

        saveEvent(paymentId, idempotencyKey, PaymentEventType.AUTHORIZATION_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of authorize on bank success. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code AUTHORIZED}, stores {@code bank_auth_id} and {@code expires_at}</li>
     *   <li>Unlocks the idempotency key, stores 200 and serialized payment as response body</li>
     *   <li>Writes the {@code AUTHORIZATION_SUCCEEDED} event</li>
     * </ul>
     */
    @Transactional
    public Payment authorizePhaseTwoSuccess(Payment payment, IdempotencyKey idempotencyKeyEntity,
            BankAuthorizationResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setBankAuthId(bankResponse.authorizationId());
        payment.setExpiresAt(bankResponse.expiresAt());
        payment.setAuthorizedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(payment));

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_SUCCEEDED);

        return payment;
    }

    /**
     * Phase 2 of authorize on permanent bank failure. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code FAILED}, sets {@code failed_at}</li>
     *   <li>Unlocks the idempotency key, stores the error response code and message</li>
     *   <li>Writes the {@code AUTHORIZATION_FAILED} event</li>
     * </ul>
     */
    @Transactional
    public void authorizePhaseTwoFailure(Payment payment, IdempotencyKey idempotencyKeyEntity,
            GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_FAILED);
    }

    /**
     * Phase 1 of capture. Atomically:
     * <ul>
     *   <li>Loads the payment with {@code SELECT FOR UPDATE}, blocking competing void operations</li>
     *   <li>Validates and transitions status to {@code CAPTURING} via the state machine</li>
     *   <li>Inserts the idempotency key row with {@code locked_at} set</li>
     *   <li>Writes the {@code CAPTURE_REQUESTED} event</li>
     * </ul>
     *
     * <p>The {@code CAPTURING} intermediate status serves two purposes: it blocks a concurrent
     * void from reading {@code AUTHORIZED} and proceeding, and it signals the reconciliation
     * worker to replay the capture on crash recovery.
     *
     * <p>The {@code SELECT FOR UPDATE} lock is released when this transaction commits —
     * before the bank call is made.
     *
     * @param customerId needed to scope the idempotency key correctly
     * @param requestHash SHA-256 of the payment ID, stored for hash mismatch detection
     */
    @Transactional
    public Payment capturePhaseOne(UUID paymentId, UUID idempotencyKey, String requestHash) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.CAPTURING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.lock(idempotencyKey, paymentId, requestHash);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of capture on bank success. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code CAPTURED}, stores {@code bank_capture_id} and {@code captured_at}</li>
     *   <li>Unlocks the idempotency key, stores 200 and serialized payment as response body</li>
     *   <li>Writes the {@code CAPTURE_SUCCEEDED} event</li>
     * </ul>
     */
    @Transactional
    public Payment capturePhaseTwoSuccess(Payment payment, IdempotencyKey idempotencyKeyEntity,
            BankCaptureResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setBankCaptureId(bankResponse.captureId());
        payment.setCaptureAt(bankResponse.capturedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(payment));

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_SUCCEEDED);

        return payment;
    }

    /**
     * Phase 2 of capture on permanent bank failure. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code FAILED}, sets {@code failed_at}</li>
     *   <li>Unlocks the idempotency key, stores the error response code and message</li>
     *   <li>Writes the {@code CAPTURE_FAILED} event</li>
     * </ul>
     */
    @Transactional
    public void capturePhaseTwoFailure(Payment payment, IdempotencyKey idempotencyKeyEntity,
            GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_FAILED);
    }

    /**
     * Phase 1 of void. Atomically:
     * <ul>
     *   <li>Loads the payment with {@code SELECT FOR UPDATE}, blocking competing capture operations</li>
     *   <li>Validates and transitions status to {@code VOIDING} via the state machine</li>
     *   <li>Inserts the idempotency key row with {@code locked_at} set</li>
     *   <li>Writes the {@code VOID_REQUESTED} event</li>
     * </ul>
     *
     * <p>The {@code VOIDING} intermediate status blocks a concurrent capture from reading
     * {@code AUTHORIZED} and proceeding, and signals the reconciliation worker on crash recovery.
     */

    @Transactional
    public Payment voidPhaseOne(UUID paymentId, UUID idempotencyKey, String requestHash) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.VOIDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.lock(idempotencyKey, paymentId, requestHash);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of void on bank success. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code VOIDED}, stores {@code bank_void_id} and {@code voided_at}</li>
     *   <li>Unlocks the idempotency key, stores 200 and serialized payment as response body</li>
     *   <li>Writes the {@code VOID_SUCCEEDED} event</li>
     * </ul>
     */
    @Transactional
    public Payment voidPhaseTwoSuccess(Payment payment, IdempotencyKey idempotencyKeyEntity,
            BankVoidResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.VOIDED);
        payment.setBankVoidId(bankResponse.voidId());
        payment.setVoidedAt(bankResponse.voidedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(payment));

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_SUCCEEDED);

        return payment;
    }

    /**
     * Phase 2 of void on permanent bank failure. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code FAILED}, sets {@code failed_at}</li>
     *   <li>Unlocks the idempotency key, stores the error response code and message</li>
     *   <li>Writes the {@code VOID_FAILED} event</li>
     * </ul>
     */
    @Transactional
    public void voidPhaseTwoFailure(Payment payment, IdempotencyKey idempotencyKeyEntity,
            GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_FAILED);
    }

    /**
     * Phase 1 of refund. Atomically:
     * <ul>
     *   <li>Loads the payment with {@code SELECT FOR UPDATE}</li>
     *   <li>Validates and transitions status to {@code REFUNDING} via the state machine</li>
     *   <li>Inserts the idempotency key row with {@code locked_at} set</li>
     *   <li>Writes the {@code REFUND_REQUESTED} event</li>
     * </ul>
     *
     * <p>The {@code REFUNDING} intermediate status signals the reconciliation worker
     * to replay the refund on crash recovery.
     */
    @Transactional
    public Payment refundPhaseOne(UUID paymentId, UUID idempotencyKey, String requestHash) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.REFUNDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.lock(idempotencyKey, paymentId, requestHash);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_REQUESTED);

        return payment;
    }

    /**
     * Phase 2 of refund on bank success. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code REFUNDED}, stores {@code bank_refund_id} and {@code refunded_at}</li>
     *   <li>Unlocks the idempotency key, stores 200 and serialized payment as response body</li>
     *   <li>Writes the {@code REFUND_SUCCEEDED} event</li>
     * </ul>
     */
    @Transactional
    public Payment refundPhaseTwoSuccess(Payment payment, IdempotencyKey idempotencyKeyEntity,
            BankRefundResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setBankRefundId(bankResponse.refundId());
        payment.setRefundedAt(bankResponse.refundedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, 200, serialize(payment));

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_SUCCEEDED);

        return payment;
    }

    /**
     * Phase 2 of refund on permanent bank failure. Atomically:
     * <ul>
     *   <li>Transitions payment to {@code FAILED}, sets {@code failed_at}</li>
     *   <li>Unlocks the idempotency key, stores the error response code and message</li>
     *   <li>Writes the {@code REFUND_FAILED} event</li>
     * </ul>
     */
    @Transactional
    public void refundPhaseTwoFailure(Payment payment, IdempotencyKey idempotencyKeyEntity,
            GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        idempotencyKeyService.unlock(idempotencyKeyEntity, ex.getStatus().value(), ex.getMessage());

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_FAILED);
    }
    
    /**
     * Writes a {@link PaymentEvent} row for the given payment. Called once in every phase
     * method — extracted to eliminate duplication across 12 phase methods.
     *
     * <p>Participates in the calling method's transaction via {@code REQUIRED} propagation.
     */
    private void saveEvent(UUID paymentId, UUID idempotencyKey, PaymentEventType eventType) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(paymentId);
        event.setIdempotencyKey(idempotencyKey);
        event.setEventType(eventType);
        event.setCreatedAt(Instant.now());
        paymentEventRepository.save(event);
    }

    /**
     * Serializes a payment to JSON for storage in {@code idempotency_keys.response_body},
     * enabling duplicate requests to replay the original response without hitting the bank.
     *
     * <p>Uses the shared {@link ObjectMapper} bean (snake_case configured) to ensure
     * the stored JSON matches what the client originally received.
     */
    private String serialize(Payment payment) {
        try {
            return objectMapper.writeValueAsString(payment);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payment", e);
        }
    }
}