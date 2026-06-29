package com.ficmart.gateway.payment;

import com.ficmart.gateway.common.InvalidTransitionException;

/**
 * State machine for the payment lifecycle.
 *
 * <p>Intermediate statuses ({@code CAPTURING}, {@code VOIDING}, {@code REFUNDING}) serve two purposes:
 * <ol>
 *   <li>Concurrency guard — a competing operation reads the intermediate status under
 *       {@code SELECT FOR UPDATE} and the state machine rejects the transition, preventing
 *       e.g. a void from proceeding while a capture is in flight.</li>
 *   <li>Crash recovery signal — the reconciliation worker scans for payments stuck in these
 *       states and replays the bank call idempotently to resolve them.</li>
 * </ol>
 *
 * <p>Terminal states ({@code VOIDED}, {@code REFUNDED}, {@code EXPIRED}, {@code FAILED})
 * accept no further transitions. {@code FAILED} covers permanent bank rejections from any
 * operation; the {@code payment_events} log records which specific operation failed and why.
 */
public enum PaymentStatus {
    PENDING,     
    AUTHORIZED,
    CAPTURING,
    VOIDING,
    REFUNDING,
    CAPTURED,
    VOIDED,
    REFUNDED,
    EXPIRED,
    FAILED;

    /**
     * Transitions this status to {@code next}, enforcing the valid lifecycle rules.
     *
     * @throws InvalidTransitionException if the transition is not permitted
     */
    public PaymentStatus transitionTo(PaymentStatus next) {
        if (!canTransitionTo(next)) {
            throw new InvalidTransitionException(
                "Cannot transition from " + this + " to " + next
            );
        }
        return next;
    }

    private boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING    -> next == AUTHORIZED || next == FAILED;
            case AUTHORIZED -> next == CAPTURING  || next == VOIDING || next == EXPIRED;
            case CAPTURING  -> next == CAPTURED   || next == FAILED;
            case VOIDING    -> next == VOIDED     || next == FAILED;
            case REFUNDING  -> next == REFUNDED   || next == FAILED;
            case CAPTURED   -> next == REFUNDING;
            case VOIDED,
                 REFUNDED,
                 EXPIRED,
                 FAILED     -> false;
        };
    }
}