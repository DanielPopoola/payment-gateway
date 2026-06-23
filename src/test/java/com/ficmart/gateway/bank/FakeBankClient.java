package com.ficmart.gateway.bank;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;

import com.ficmart.gateway.common.GatewayException;

public class FakeBankClient implements BankClient {

    private final AtomicInteger authorizeCalls = new AtomicInteger(0);
    private boolean failNextAuthorize = false;
    private boolean failNextCapture = false;

    public void failNextAuthorize() { this.failNextAuthorize = true; }
    public void failNextCapture()   { this.failNextCapture = true; }
    public int authorizeCallCount() { return authorizeCalls.get(); }

    @Override
    public BankAuthorizationResponse authorize(BankAuthorizeRequest request, String idempotencyKey) {
        authorizeCalls.incrementAndGet();
        if (failNextAuthorize) {
            failNextAuthorize = false;
            throw new GatewayException("Card expired", HttpStatus.BAD_REQUEST, "card_expired", null);
        }
        return new BankAuthorizationResponse(
            request.amount(),
            "auth_" + UUID.randomUUID(),
            Instant.now(),
            "USD",
            Instant.now().plus(7, ChronoUnit.DAYS),
            "approved"
        );
    }

    @Override
    public BankCaptureResponse capture(BankCaptureRequest request, String idempotencyKey) {
        if (failNextCapture) {
            failNextCapture = false;
            throw new GatewayException("Authorization not found", HttpStatus.BAD_REQUEST, "authorization_not_found", null);
        }
        return new BankCaptureResponse(
            request.amount(),
            request.authorizationId(),
            "capture_" + UUID.randomUUID(),
            Instant.now(),
            "USD",
            "captured"
        );
    }

    @Override
    public BankVoidResponse void_(BankVoidRequest request, String idempotencyKey) {
        return new BankVoidResponse(
            request.authorizationId(),
            "voided",
            "void_" + UUID.randomUUID(),
            Instant.now()
        );
    }

    @Override
    public BankRefundResponse refund(BankRefundRequest request, String idempotencyKey) {
        return new BankRefundResponse(
            request.amount(),
            request.captureId(),
            "USD",
            "refund_" + UUID.randomUUID(),
            Instant.now(),
            "refunded"
        );
    }

    @Override
    public BankAuthorizationResponse getAuthorization(String authorizationId) {
        return new BankAuthorizationResponse(
            5000L, authorizationId, Instant.now(), "USD",
            Instant.now().plus(7, ChronoUnit.DAYS), "approved"
        );
    }

    public void reset() {
        authorizeCalls.set(0);
        failNextAuthorize = false;
        failNextCapture = false;
    }
}
