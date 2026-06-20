package com.ficmart.gateway.bank;

public record RefundRequest(
    Long amount,
    String captureId
) {}