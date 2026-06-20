package com.ficmart.gateway.common;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object details;

    public GatewayException(String message, HttpStatus status, String code, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Object getDetails() { return details; }
}
