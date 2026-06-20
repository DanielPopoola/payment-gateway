package com.ficmart.gateway.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.ficmart.gateway.common.InvalidTransitionException;

public class PaymentStatusTest {

    @Test
    void pendingCanTransitionToAuthorized() {
        PaymentStatus result = PaymentStatus.PENDING.transitionTo(PaymentStatus.AUTHORIZED);
        assertEquals(PaymentStatus.AUTHORIZED, result);
    }

    @Test
    void pendingCanTransitionToFailed() {
        PaymentStatus result = PaymentStatus.PENDING.transitionTo(PaymentStatus.FAILED);
        assertEquals(PaymentStatus.FAILED, result);        
    }

    @Test
    void authorizedCanTransitionToCapturing() {
        PaymentStatus result = PaymentStatus.AUTHORIZED.transitionTo(PaymentStatus.CAPTURING);
        assertEquals(PaymentStatus.CAPTURING, result);
    }

    @Test
    void authorizedCanTransitionToVoiding() {
        PaymentStatus result = PaymentStatus.AUTHORIZED.transitionTo(PaymentStatus.VOIDING);
        assertEquals(PaymentStatus.VOIDING, result);
    }

    @Test
    void authorizedCanTransitionToExpired() {
        PaymentStatus result = PaymentStatus.AUTHORIZED.transitionTo(PaymentStatus.EXPIRED);
        assertEquals(PaymentStatus.EXPIRED, result);
    }

    @Test
    void capturingCanTransitionToCaptured() {
        PaymentStatus result = PaymentStatus.CAPTURING.transitionTo(PaymentStatus.CAPTURED);
        assertEquals(PaymentStatus.CAPTURED, result);
    }

    @Test
    void capturingCanTransitionToFailed() {
        PaymentStatus result = PaymentStatus.CAPTURING.transitionTo(PaymentStatus.FAILED);
        assertEquals(PaymentStatus.FAILED, result);
    }

    @Test
    void capturedCanTransitionToRefunding() {
        PaymentStatus result = PaymentStatus.CAPTURED.transitionTo(PaymentStatus.REFUNDING);
        assertEquals(PaymentStatus.REFUNDING, result);
    }

    @Test
    void voidingCanTransitionToVoided() {
        PaymentStatus result = PaymentStatus.VOIDING.transitionTo(PaymentStatus.VOIDED);
        assertEquals(PaymentStatus.VOIDED, result);
    }

    @Test
    void refundingCanTransitionToRefunded() {
        PaymentStatus result = PaymentStatus.REFUNDING.transitionTo(PaymentStatus.REFUNDED);
        assertEquals(PaymentStatus.REFUNDED, result);
    }

    @Test
    void voidedIsTerminal() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.VOIDED.transitionTo(PaymentStatus.AUTHORIZED)
        );
    }

    @Test
    void refundedIsTerminal() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.REFUNDED.transitionTo(PaymentStatus.AUTHORIZED)
        );
    }

    @Test
    void expiredIsTerminal() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.EXPIRED.transitionTo(PaymentStatus.AUTHORIZED)
        );
    }

    @Test
    void failedIsTerminal() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.FAILED.transitionTo(PaymentStatus.AUTHORIZED)
        );
    }

    @Test
    void authorizedCannotSkipToCapture() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.AUTHORIZED.transitionTo(PaymentStatus.CAPTURED)
        );
    }

    @Test
    void voidedCannotSkipToCapture() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.VOIDED.transitionTo(PaymentStatus.CAPTURED)
        );
    }

    @Test
    void pendingCannotSkipToCapturing() {
        assertThrows(InvalidTransitionException.class, () ->
            PaymentStatus.PENDING.transitionTo(PaymentStatus.CAPTURED)
        );
    }
}
