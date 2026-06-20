package com.ficmart.gateway.common;

public record ApiErrorResponse(boolean success, String message, ApiErrorBody error) {}
