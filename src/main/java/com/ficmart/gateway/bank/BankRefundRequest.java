package com.ficmart.gateway.bank;

public record BankRefundRequest(
    Long amount,
    String captureId
) {}