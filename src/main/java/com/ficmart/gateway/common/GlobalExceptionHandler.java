package com.ficmart.gateway.common;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized exception handler for all controllers.
 *
 * <p>Catches exceptions thrown anywhere in the application and converts them into
 * the standardized {@link ApiErrorResponse} envelope. No controller ever hand-builds
 * error JSON — all error formatting is done here.
 *
 * <p>Handled exceptions:
 * <ul>
 *   <li>{@link GatewayException} — uses the exception's own status and error code</li>
 *   <li>{@link InvalidTransitionException} — 409 Conflict, code {@code invalid_transition}</li>
 *   <li>{@link MethodArgumentNotValidException} — 400 Bad Request, code {@code validation_error},
 *       field errors included in {@code details}</li>
 *   <li>{@link Exception} — 500 Internal Server Error, no stack trace leaked to client</li>
 * </ul>
 */
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponses.error(ex.getMessage(), "validation_error", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponses.error("Invalid value: " + ex.getValue(), "validation_error", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneralException(Exception ex) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponses.error("An unexpected error occurred", "internal_error", null));
    }
}