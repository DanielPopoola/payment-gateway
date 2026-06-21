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

@Service
public class IdempotencyKeyService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyKeyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper =  objectMapper;
    }

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

    @Transactional
    public void unlock(IdempotencyKey entity, Integer responseCode, String responseBody) {
        entity.setLockedAt(null);
        entity.setResponseCode(responseCode);
        entity.setResponseBody(responseBody);
        idempotencyRepository.save(entity);
    }
}