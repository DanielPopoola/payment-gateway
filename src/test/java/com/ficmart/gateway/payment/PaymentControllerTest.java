package com.ficmart.gateway.payment;

import com.ficmart.gateway.common.GatewayException;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
public class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @SuppressWarnings("unused")
    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class FakeConfig {
        @Bean
        public PaymentService paymentService() {
            return new PaymentService(null, null, null, null, null) {
                @Override
                public Payment authorize(AuthorizeRequest request, UUID idempotencyKey) {
                    throw new GatewayException("Payment not found", HttpStatus.NOT_FOUND, "payment_not_found", null);
                }

                @Override
                public Payment capture(UUID paymentId, UUID idempotencyKey) {
                    throw new GatewayException("Payment not found", HttpStatus.NOT_FOUND, "payment_not_found", null);
                }

                @Override
                public Payment void_(UUID paymentId, UUID idempotencyKey) {
                    throw new GatewayException("Payment not found", HttpStatus.NOT_FOUND, "payment_not_found", null);
                }

                @Override
                public Payment refund(UUID paymentId, UUID idempotencyKey) {
                    throw new GatewayException("Payment not found", HttpStatus.NOT_FOUND, "payment_not_found", null);
                }

                @Override
                public Payment getPayment(@NonNull UUID paymentId) {
                    throw new GatewayException("Payment not found", HttpStatus.NOT_FOUND, "payment_not_found", null);
                }

                @Override
                public List<Payment> getPaymentByOrderId(String orderId) { return List.of(); }

                @Override
                public List<Payment> getPaymentByCustomerId(Long customerId) { return List.of(); }

                @Override
                public List<PaymentEvent> getPaymentEvents(UUID paymentId) { return List.of(); }
            };
        }
    }

    // --- POST /payments/authorize ---

    @Test
    void authorize_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAuthorizeBody()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void authorize_negativeAmount_returns400() throws Exception {
        String body = """
            {
              "card_number": "4111111111111111",
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": -100,
              "order_id": "order_1",
              "customer_id": 42
            }
            """;
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void authorize_zeroAmount_returns400() throws Exception {
        String body = """
            {
              "card_number": "4111111111111111",
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": 0,
              "order_id": "order_1",
              "customer_id": 42
            }
            """;
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void authorize_missingCardNumber_returns400() throws Exception {
        String body = """
            {
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": 5000,
              "order_id": "order_1",
              "customer_id": 42
            }
            """;
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void authorize_missingOrderId_returns400() throws Exception {
        String body = """
            {
              "card_number": "4111111111111111",
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": 5000,
              "customer_id": 42
            }
            """;
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void authorize_missingCustomerId_returns400() throws Exception {
        String body = """
            {
              "card_number": "4111111111111111",
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": 5000,
              "order_id": "order_1"
            }
            """;
        mockMvc.perform(post("/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    // --- POST /payments/{id}/capture, void, refund ---

    @Test
    void capture_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/capture", UUID.randomUUID()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void capture_invalidUuid_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/capture", "not-a-uuid")
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void capture_paymentNotFound_returns404() throws Exception {
        mockMvc.perform(post("/payments/{id}/capture", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("payment_not_found"));
    }

    @Test
    void void_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/void", UUID.randomUUID()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void void_invalidUuid_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/void", "not-a-uuid")
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void void_paymentNotFound_returns404() throws Exception {
        mockMvc.perform(post("/payments/{id}/void", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("payment_not_found"));
    }

    @Test
    void refund_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/refund", UUID.randomUUID()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void refund_invalidUuid_returns400() throws Exception {
        mockMvc.perform(post("/payments/{id}/refund", "not-a-uuid")
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void refund_paymentNotFound_returns404() throws Exception {
        mockMvc.perform(post("/payments/{id}/refund", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("payment_not_found"));
    }

    // --- GET /payments/{id} ---

    @Test
    void getPayment_notFound_returns404() throws Exception {
        mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("payment_not_found"));
    }

    @Test
    void getPayment_invalidUuid_returns400() throws Exception {
        mockMvc.perform(get("/payments/{id}", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    // --- helper ---

    private String validAuthorizeBody() {
        return """
            {
              "card_number": "4111111111111111",
              "cvv": "123",
              "expiry_month": 12,
              "expiry_year": 2030,
              "amount_cents": 5000,
              "order_id": "order_1",
              "customer_id": 42
            }
            """;
    }
}