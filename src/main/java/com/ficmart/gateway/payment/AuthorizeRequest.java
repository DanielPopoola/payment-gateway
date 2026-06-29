package com.ficmart.gateway.payment;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;


public record AuthorizeRequest(
    @NotNull String cardNumber,
    @NotNull String cvv,
    @Positive Integer expiryMonth,
    @Positive Integer expiryYear,
    @Positive Long amountCents,
    @NotNull String orderId,
    @Positive Long customerId
) {}
