package com.ficmart.gateway.bank;

/**
 * Testability seam for bank communication.
 *
 * <p>All service-layer code depends on this interface, never on concrete implementations.
 * This allows tests to inject a hand-rolled fake without touching HTTP or the real bank.
 *
 * <p>Every method accepts an {@code idempotencyKey} that is forwarded as the
 * {@code Idempotency-Key} header on the bank request, ensuring safe retries.
 */
public interface BankClient {
    BankAuthorizationResponse authorize(BankAuthorizeRequest request, String idempotencyKey);
    BankCaptureResponse capture(BankCaptureRequest request, String idempotencyKey);
    BankVoidResponse void_(BankVoidRequest request, String idempotencyKey);
    BankRefundResponse refund(BankRefundRequest request, String idempotencyKey);
}
