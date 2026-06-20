package com.ficmart.gateway.bank;

import java.time.Instant;

public record RefundResponse(
    Long amount,
    String captureId,
    String currency,
    String refundId,
    Instant refundedAt,
    String status
) {}