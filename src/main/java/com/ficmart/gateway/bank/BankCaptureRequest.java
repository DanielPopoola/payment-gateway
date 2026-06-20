package com.ficmart.gateway.bank;

public record BankCaptureRequest(
    Long amount,
    String authorizationId
) {}
