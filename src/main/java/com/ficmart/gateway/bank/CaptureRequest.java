package com.ficmart.gateway.bank;

public record CaptureRequest(
    Long amount,
    String authorizationId
) {}
