package com.ficmart.gateway.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ficmart.gateway.common.ApiResponse;
import com.ficmart.gateway.common.ApiResponses;
import com.ficmart.gateway.common.GatewayException;

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

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> getPayment(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(ApiResponses.success(paymentService.getPayment(id)));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<ApiResponse<?>> getPaymentEvents(@PathVariable("id") UUID paymentId) {
        return ResponseEntity.ok(ApiResponses.success(paymentService.getPaymentEvents(paymentId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> getPayments(
            @RequestParam(value = "order_id", required = false) String orderId,
            @RequestParam(value = "customer_id", required = false) Long customerId) {
        if (orderId != null) {
            return ResponseEntity.ok(ApiResponses.success(paymentService.getPaymentByOrderId(orderId)));
        }
        if (customerId != null) {
            return ResponseEntity.ok(ApiResponses.success(paymentService.getPaymentByCustomerId(customerId)));
        }
        throw new GatewayException("Provide order_id or customer_id", 
            HttpStatus.BAD_REQUEST, "missing_query_param", null);
    }
}
