package com.ficmart.gateway.payment;

import java.util.*;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.idempotency.IdempotencyKey;
import com.ficmart.gateway.idempotency.IdempotencyKeyService;
import com.ficmart.gateway.idempotency.IdempotencyRepository;

import org.springframework.data.domain.PageRequest;

/**
 * Orchestrates the full lifecycle of payment operations.
 *
 * <p>This service contains no transaction logic and no atomic writes of its own.
 * Its sole responsibility is sequencing:
 * <ol>
 *   <li>Idempotency check — short-circuit on duplicate requests</li>
 *   <li>Phase 1 — atomic DB writes via {@link PaymentTransactionService}</li>
 *   <li>Bank call — external HTTP request via {@link BankClient}</li>
 *   <li>Phase 2 — atomic DB writes via {@link PaymentTransactionService}</li>
 * </ol>
 *
 * <p>{@code @Transactional} is intentionally absent from this class. Wrapping the full
 * flow in one transaction would hold a DB connection open across the bank HTTP call.
 *
 * <p>Between phase 1 and the bank call, the idempotency key entity is retrieved from the
 * database so it can be passed into phase 2 for unlocking. This lookup is safe because
 * phase 1 has already committed the row.
 */
@Service
public class PaymentService {

    private final PaymentTransactionService transactionService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final IdempotencyRepository idempotencyRepository;
    private final BankClient bankClient;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentService(PaymentTransactionService transactionService,
            IdempotencyKeyService idempotencyKeyService,
            IdempotencyRepository idempotencyRepository,
            BankClient bankClient,
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository) {
        this.transactionService = transactionService;
        this.idempotencyKeyService = idempotencyKeyService;
        this.idempotencyRepository = idempotencyRepository;
        this.bankClient = bankClient;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    /**
     * Authorizes a payment by reserving funds on the provided card.
     *
     * <p>Creates a new {@link Payment} row. Unlike capture/void/refund, the payment row
     * does not exist before this call, so no pre-fetch or {@code SELECT FOR UPDATE} is needed.
     * {@code customerId} comes directly from the request.
     *
     * @param request card details and amount from FicMart
     * @param idempotencyKey client-provided UUID for deduplication
     * @return the authorized payment receipt
     */
    public Payment authorize(AuthorizeRequest request, UUID idempotencyKey) {
        String requestHash = hashRequest(request.cardNumber(), request.amountCents(),
            request.orderId(), request.customerId());

        Payment replay = idempotencyKeyService.checkAndReplay(
            request.customerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment payment = transactionService.authorizePhaseOne(request, idempotencyKey, requestHash);

        IdempotencyKey idempotencyKeyEntity = idempotencyRepository
            .findByCustomerIdAndIdempotencyKey(request.customerId(), idempotencyKey);

        try {
            BankAuthorizationResponse bankResponse = bankClient.authorize(
                new BankAuthorizeRequest(request.amountCents(), request.cardNumber(),
                    request.cvv(), request.expiryMonth(), request.expiryYear()),
                idempotencyKey.toString());

            return transactionService.authorizePhaseTwoSuccess(
                payment, idempotencyKeyEntity, bankResponse, idempotencyKey);

        } catch (GatewayException ex) {
            transactionService.authorizePhaseTwoFailure(
                payment, idempotencyKeyEntity, ex, idempotencyKey);
            throw ex;
        }
    }

    /**
     * Captures a previously authorized payment, charging the reserved funds.
     *
     * <p>Pre-fetches the payment (without lock) to retrieve {@code customerId} for the
     * idempotency check. Phase 1 then re-loads the payment with {@code SELECT FOR UPDATE}
     * to block concurrent void operations.
     */
    public Payment capture(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(
            payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.capturePhaseOne(
            paymentId, idempotencyKey, payment.getCustomerId(), requestHash);

        IdempotencyKey idempotencyKeyEntity = idempotencyRepository
            .findByCustomerIdAndIdempotencyKey(payment.getCustomerId(), idempotencyKey);

        try {
            BankCaptureResponse bankResponse = bankClient.capture(
                new BankCaptureRequest(lockedPayment.getAmountCents(), lockedPayment.getBankAuthId()),
                idempotencyKey.toString());

            return transactionService.capturePhaseTwoSuccess(
                lockedPayment, idempotencyKeyEntity, bankResponse, idempotencyKey);

        } catch (GatewayException ex) {
            transactionService.capturePhaseTwoFailure(
                lockedPayment, idempotencyKeyEntity, ex, idempotencyKey);
            throw ex;
        }
    }

     /**
     * Voids a previously authorized payment, releasing the reserved funds.
     * Cannot be called after capture — the state machine enforces this in phase 1.
     */
    public Payment void_(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(
            payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.voidPhaseOne(
            paymentId, idempotencyKey, payment.getCustomerId(), requestHash);

        IdempotencyKey idempotencyKeyEntity = idempotencyRepository
            .findByCustomerIdAndIdempotencyKey(payment.getCustomerId(), idempotencyKey);

        try {
            BankVoidResponse bankResponse = bankClient.void_(
                new BankVoidRequest(lockedPayment.getBankAuthId()),
                idempotencyKey.toString());

            return transactionService.voidPhaseTwoSuccess(
                lockedPayment, idempotencyKeyEntity, bankResponse, idempotencyKey);

        } catch (GatewayException ex) {
            transactionService.voidPhaseTwoFailure(
                lockedPayment, idempotencyKeyEntity, ex, idempotencyKey);
            throw ex;
        }
    }

    /**
     * Refunds a captured payment, returning funds to the customer.
     * Cannot be called before capture — the state machine enforces this in phase 1.
     */
    public Payment refund(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        String requestHash = hashRequest(paymentId);

        Payment replay = idempotencyKeyService.checkAndReplay(
            payment.getCustomerId(), idempotencyKey, requestHash);
        if (replay != null) return replay;

        Payment lockedPayment = transactionService.refundPhaseOne(
            paymentId, idempotencyKey, payment.getCustomerId(), requestHash);

        IdempotencyKey idempotencyKeyEntity = idempotencyRepository
            .findByCustomerIdAndIdempotencyKey(payment.getCustomerId(), idempotencyKey);

        try {
            BankRefundResponse bankResponse = bankClient.refund(
                new BankRefundRequest(lockedPayment.getAmountCents(), lockedPayment.getBankCaptureId()),
                idempotencyKey.toString());

            return transactionService.refundPhaseTwoSuccess(
                lockedPayment, idempotencyKeyEntity, bankResponse, idempotencyKey);

        } catch (GatewayException ex) {
            transactionService.refundPhaseTwoFailure(
                lockedPayment, idempotencyKeyEntity, ex, idempotencyKey);
            throw ex;
        }
    }

    /**
     * Returns a single payment receipt by its gateway reference ID.
     *
     * @throws GatewayException 404 if the payment does not exist
     */
    public Payment getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));
    }

    /**
     * Returns all payment records for the given FicMart order ID.
     *
     * <p>May return multiple records — FicMart may retry a failed payment for the same
     * order, producing a new payment row each time. Multiple records is correct history,
     * not a bug.
     */
    public List<Payment> getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    /**
     * Returns paginated payment history for the given customer.
     * Defaults to page 0, 20 results per page.
     */
    public List<Payment> getPaymentByCustomerId(Long customerId) {
        return paymentRepository.findByCustomerId(customerId, PageRequest.of(0, 20));
    }

    /**
     * Returns the ordered audit log for a payment, from first event to last.
     */
    public List<PaymentEvent> getPaymentEvents(UUID paymentId) {
        return paymentEventRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    /**
     * Computes a SHA-256 hash of the given fields joined by "|", used to detect
     * idempotency key reuse with a different payload.
     *
     * <p>For authorize: hashes card number, amount, order ID, customer ID.
     * For capture/void/refund: hashes the payment ID only.
     *
     * @param fields the values to hash
     * @return Base64-encoded SHA-256 digest
     */
    private static String hashRequest(Object... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = Arrays.stream(fields)
                .map(Object::toString)
                .collect(Collectors.joining("|"));
            return Base64.getEncoder().encodeToString(digest.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}