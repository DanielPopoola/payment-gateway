package com.ficmart.gateway.bank;

import java.time.Instant;

public record BankVoidResponse(
    String authorizationId,
    String status,
    String voidId,
    Instant voidedAt
){}
