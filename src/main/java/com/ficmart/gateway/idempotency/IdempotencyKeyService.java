package com.ficmart.gateway.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.payment.Payment;

/**
 * Manages idempotency key lifecycle for all mutating payment operations.
 *
 * <p>Implements the Stripe-style idempotency pattern:
 * <ol>
 *   <li>{@link #checkAndReplay} — guards against duplicate requests before any transaction starts</li>
 *   <li>{@link #lock} — records intent and marks the operation as in-flight (phase 1)</li>
 *   <li>{@link #unlock} — stores the final response and clears the in-flight signal (phase 2)</li>
 * </ol>
 *
 * <p>Used by all four payment operations (authorize, capture, void, refund).
 */
@Service
public class IdempotencyKeyService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyKeyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper =  objectMapper;
    }

    /**
     * Checks whether a request has been seen before and handles all four outcomes:
     * <ul>
     *   <li>Key not found → returns {@code null} (caller should proceed)</li>
     *   <li>Key found, {@code lockedAt} not null → throws 409 (request in flight)</li>
     *   <li>Key found, request hash mismatch → throws 400 (key reused with different payload)</li>
     *   <li>Key found, {@code responseCode} not null → deserializes and returns stored response (replay)</li>
     * </ul>
     *
     * @return the stored {@link Payment} on replay, or {@code null} to proceed with a new request
     * @throws GatewayException on conflict or hash mismatch
     */
    public Payment checkAndReplay(Long customerId, UUID idempotencyKey, String requestHash) {
        IdempotencyKey existing = idempotencyRepository
            .findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);

        if (existing == null) {
            return null; // proceed
        }
        if (existing.getLockedAt() != null) {
            throw new GatewayException("Request already in flight",
                HttpStatus.CONFLICT, "request_in_flight", null);
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new GatewayException("Idempotency key reused with different payload",
                HttpStatus.BAD_REQUEST, "idempotency_key_mismatch", null);
        }
        if (existing.getResponseCode() != null) {
            try {
                return objectMapper.readValue(existing.getResponseBody(), Payment.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialize stored response", e);
            }
        }
        return null; // proceed
    }

    /**
     * Inserts a new idempotency key row with {@code lockedAt} set to now, signalling
     * that this operation is currently in flight. Must be called inside phase 1 transaction.
     *
     * @return the saved entity, needed by the caller to pass to {@link #unlock}
     */
    @Transactional
    public IdempotencyKey lock(Long customerId, UUID idempotencyKey, UUID paymentId, String requestHash) {
        Instant now = Instant.now();
        IdempotencyKey entity = new IdempotencyKey();
        entity.setIdempotencyKey(idempotencyKey);
        entity.setCustomerId(customerId);
        entity.setPaymentId(paymentId);
        entity.setRequestHash(requestHash);
        entity.setLockedAt(now);
        entity.setLastRunAt(now);
        entity.setCreatedAt(now);
        return idempotencyRepository.save(entity);
    }

    /**
     * Clears the in-flight signal and stores the final response, making the key
     * replayable for future duplicate requests. Called in phase 2 after the bank responds.
     */
    @Transactional
    public void unlock(IdempotencyKey entity, Integer responseCode, String responseBody) {
        entity.setLockedAt(null);
        entity.setResponseCode(responseCode);
        entity.setResponseBody(responseBody);
        idempotencyRepository.save(entity);
    }
}