package com.ficmart.gateway.bank;

public record BankVoidRequest(
    String authorizationId
) {}