package com.ficmart.gateway.common;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiErrorResponse> handleGatewayException(GatewayException ex) {
        return ResponseEntity
            .status(ex.getStatus())
            .body(ApiResponses.error(ex.getMessage(), ex.getCode(), ex.getDetails()));
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTransitionException(InvalidTransitionException ex) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiResponses.error(ex.getMessage(), "invalid_transition", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<String> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .toList();

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponses.error("Validation failed", "validation_error", fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneralException(Exception ex) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponses.error("An unexpected error occurred", "internal_error", null));
    }
}