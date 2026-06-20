package com.ficmart.gateway.bank;

public interface BankClient {
    AuthorizationResponse authorize(AuthorizeRequest request, String idempotencyKey);
    CaptureResponse capture(CaptureRequest request, String idempotencyKey);
    VoidResponse void_(VoidRequest request, String idempotencyKey);
    RefundResponse refund(RefundRequest request, String idempotencyKey);
}
