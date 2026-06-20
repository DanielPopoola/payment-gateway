package com.ficmart.gateway.common;

public record ApiResponse<T>(boolean success, String message, T data) {}