package com.ficmart.gateway.bank;

import java.time.Instant;

public record VoidResponse(
    String authorizationId,
    String status,
    String voidId,
    Instant voidedAt
){}
