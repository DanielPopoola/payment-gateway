package com.ficmart.gateway.common;

public class InvalidTransitionException extends RuntimeException{
    public InvalidTransitionException(String message) {
        super(message);
    }
}
