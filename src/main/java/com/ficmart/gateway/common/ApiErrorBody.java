package com.ficmart.gateway.common;

public record ApiErrorBody(
    String code,
    Object details
) {}
