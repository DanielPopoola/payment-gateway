package com.ficmart.gateway.bank;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.common.GatewayException;

@Component
public class BankApiClient implements BankClient {

    private final RestClient restClient;

    public BankApiClient(@Value("${bank.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .defaultStatusHandler(status -> status.isError(), 
                (request, response) -> handleError(objectMapper, response))
            .build();
    }

    private void handleError(ObjectMapper objectMapper, ClientHttpResponse response) throws IOException {
        BankErrorResponse error = objectMapper.readValue(response.getBody(), BankErrorResponse.class);
        HttpStatus status = switch (error.error()) {
            case "insufficient_funds", 
                "invalid_card",
                "invalid_cvv",
                "card_expired",
                "amount_mismatch",
                "invalid_amount",
                "authorization_expired"                                 -> HttpStatus.BAD_REQUEST;
            case "authorization_not_found",
                "capture_not_found"                                     -> HttpStatus.NOT_FOUND;
            case "authorization_already_used",
                "already_captured",
                "already_voided",
                "already_refunded"                                       -> HttpStatus.CONFLICT;
            default                                                      -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        throw new GatewayException(error.message(), status, error.error(), null);
    }

    @Override
    public AuthorizationResponse authorize(AuthorizeRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/authorizations")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(AuthorizationResponse.class);
    }

    @Override
    public CaptureResponse capture(CaptureRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/captures")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(CaptureResponse.class);
    }

    @Override
    public VoidResponse void_(VoidRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/voids")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(VoidResponse.class);
    }
    
    @Override
    public RefundResponse refund(RefundRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/refunds")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(RefundResponse.class);
    }
}
