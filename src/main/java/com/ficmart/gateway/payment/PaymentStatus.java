package com.ficmart.gateway.payment;

import com.ficmart.gateway.common.InvalidTransitionException;

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