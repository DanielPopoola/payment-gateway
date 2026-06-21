package com.ficmart.gateway.payment;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ficmart.gateway.common.ApiResponse;
import com.ficmart.gateway.common.ApiResponses;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<ApiResponse<?>> authorize(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody @Valid AuthorizeRequest request) {
        return ResponseEntity.ok(ApiResponses.success(
            paymentService.authorize(request, idempotencyKey)
        ));   
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<ApiResponse<?>> capture(
            @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        return ResponseEntity.ok(ApiResponses.success(paymentService.capture(id, idempotencyKey)));
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<ApiResponse<?>> void_(
            @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        return ResponseEntity.ok(ApiResponses.success(paymentService.void_(id, idempotencyKey)));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<ApiResponse<?>> refund(
            @PathVariable("id") UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        return ResponseEntity.ok(ApiResponses.success(paymentService.refund(id, idempotencyKey)));
    }
}
