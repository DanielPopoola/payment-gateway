package com.ficmart.gateway.bank;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.common.GatewayException;

/**
 * HTTP implementation of {@link BankClient} that communicates with the mock bank API.
 *
 * <p>Configured with a snake_case {@link ObjectMapper} via {@link MappingJackson2HttpMessageConverter}
 * so that Java camelCase fields (e.g. {@code cardNumber}) are serialized correctly to the bank's
 * expected snake_case format (e.g. {@code card_number}).
 *
 * <p>All bank error codes are mapped to {@link GatewayException} in one place via
 * {@link #handleError}. Callers never inspect raw HTTP status codes.
 */
@Component
public class BankApiClient implements BankClient {

    private final RestClient restClient;

    public BankApiClient(@Value("${bank.base-url}") String baseUrl, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .messageConverters(converters -> {
                converters.clear();
                converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
            })
            .defaultStatusHandler(status -> status.isError(), 
                (request, response) -> handleError(objectMapper, response))
            .build();
    }

    /**
     * Handles error responses from the bank by deserializing the error body and mapping
     * the error code to an appropriate HTTP status before throwing a {@link GatewayException}.
     *
     * <p>4xx errors are permanent failures — no retry. 5xx errors (default case) are
     * transient and will be retried by {@link RetryingBankClient}.
     */
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
    public BankAuthorizationResponse authorize(BankAuthorizeRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/authorizations")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankAuthorizationResponse.class);
    }

    @Override
    public BankCaptureResponse capture(BankCaptureRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/captures")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankCaptureResponse.class);
    }

    @Override
    public BankVoidResponse void_(BankVoidRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/voids")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankVoidResponse.class);
    }
    
    @Override
    public BankRefundResponse refund(BankRefundRequest request, String idempotencyKey) {
        return restClient.post()
            .uri("/api/v1/refunds")
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankRefundResponse.class);
    }
}
