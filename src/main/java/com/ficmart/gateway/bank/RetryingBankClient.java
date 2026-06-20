package com.ficmart.gateway.bank;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ficmart.gateway.common.GatewayException;

@Primary
@Component
public class RetryingBankClient implements BankClient {

    private final BankClient delegate;
    private final int maxAttempts;
    private final Duration baseDelay;

    public RetryingBankClient(
            @Qualifier("bankApiClient") BankClient delegate,
            @Value("${bank.retry.max-attempts}") int maxAttempts,
            @Value("${bank.retry.base-delay-ms}") long baseDelayMs) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.baseDelay = Duration.ofMillis(baseDelayMs);
    }

    @Override
    public AuthorizationResponse authorize(AuthorizeRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.authorize(request, idempotencyKey));
    }

    @Override
    public CaptureResponse capture(CaptureRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.capture(request, idempotencyKey));
    }

    @Override
    public VoidResponse void_(VoidRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.void_(request, idempotencyKey));
    }

    @Override
    public RefundResponse refund(RefundRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.refund(request, idempotencyKey));
    }

    private <T> T withRetry(Supplier<T> operation) {
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (GatewayException ex) {
                if (ex.getStatus().is4xxClientError() || attempt >= maxAttempts - 1) {
                    throw ex;
                }
                sleepWithBackoff(attempt);
                attempt++;
            }
        }
    }
    
    private void sleepWithBackoff(int attempt) {
        try {
            long jitterMillis = (long) (Math.random() * baseDelay.toMillis());
            long delayMillis = baseDelay.toMillis() * (1L << attempt) + jitterMillis;
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Retry interrupted", 
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, 
                "retry_interrupted", null);
        }
    }
}
