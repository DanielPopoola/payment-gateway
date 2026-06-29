package com.ficmart.gateway.bank;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ficmart.gateway.common.GatewayException;

/**
 * Decorator around {@link BankClient} that adds exponential backoff with jitter on transient failures.
 *
 * <p>Retry policy:
 * <ul>
 *   <li>Retries on 5xx responses and {@link IOException} (transient failures)</li>
 *   <li>Fails fast on 4xx responses (permanent bank rejections)</li>
 *   <li>Backoff formula: {@code baseDelay * 2^attempt + random(0, baseDelay)}</li>
 * </ul>
 *
 * <p>Registered as {@code @Primary} so all injection points receive the retrying version.
 * {@link BankApiClient} is injected via {@code @Qualifier} to avoid circular dependency.
 */
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
    public BankAuthorizationResponse authorize(BankAuthorizeRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.authorize(request, idempotencyKey));
    }

    @Override
    public BankCaptureResponse capture(BankCaptureRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.capture(request, idempotencyKey));
    }

    @Override
    public BankVoidResponse void_(BankVoidRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.void_(request, idempotencyKey));
    }

    @Override
    public BankRefundResponse refund(BankRefundRequest request, String idempotencyKey) {
        return withRetry(() -> delegate.refund(request, idempotencyKey));
    }

    /**
     * Executes the given bank operation with retry logic.
     *
     * @param operation a supplier wrapping the bank call to execute
     * @param <T> the response type
     * @return the bank response on success
     * @throws GatewayException immediately on 4xx, or after exhausting retries on 5xx
     */
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

    /**
     * Sleeps for an exponentially increasing duration with random jitter to avoid
     * thundering herd when multiple gateway instances retry simultaneously.
     *
     * @param attempt zero-based attempt index
     */
    private void sleepWithBackoff(int attempt) {
        try {
            long jitterMillis = (long) (Math.random() * baseDelay.toMillis());
            long delayMillis = baseDelay.toMillis() * (1L << attempt) + jitterMillis;
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Retry interrupted", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "retry_interrupted", null);
        }
    }
}
