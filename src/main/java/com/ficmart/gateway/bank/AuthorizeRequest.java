package com.ficmart.gateway.bank;

public record AuthorizeRequest(
    Long amount,
    String cardNumber,
    String cvv,
    Integer expiryMonth,
    Integer expiryYear
) {}