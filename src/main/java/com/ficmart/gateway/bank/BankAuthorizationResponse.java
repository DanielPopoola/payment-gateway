package com.ficmart.gateway.bank;

import java.time.Instant;

public record BankAuthorizationResponse(
    Long amount,
    String authorizationId,
    Instant createdAt,
    String currency,
    Instant expiresAt,
    String status
) {}
