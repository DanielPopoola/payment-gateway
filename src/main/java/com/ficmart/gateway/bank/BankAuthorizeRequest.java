package com.ficmart.gateway.bank;

public record BankAuthorizeRequest(
    Long amount,
    String cardNumber,
    String cvv,
    Integer expiryMonth,
    Integer expiryYear
) {}