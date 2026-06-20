package com.ficmart.gateway.bank;

public interface BankClient {
    BankAuthorizationResponse authorize(BankAuthorizeRequest request, String idempotencyKey);
    BankCaptureResponse capture(BankCaptureRequest request, String idempotencyKey);
    BankVoidResponse void_(BankVoidRequest request, String idempotencyKey);
    BankRefundResponse refund(BankRefundRequest request, String idempotencyKey);
}
