package com.ficmart.gateway.bank;

import java.time.Instant;

public record CaptureResponse(
    Long amount,
    String authorizationId,
    String captureId,
    Instant capturedAt,
    String currency,
    String status
) {}