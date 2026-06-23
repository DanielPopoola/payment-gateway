package com.ficmart.gateway.idempotency;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ficmart.gateway.payment.PaymentOperation;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, Long> {
    
    IdempotencyKey findByIdempotencyKey(UUID key);    

    IdempotencyKey findByPaymentIdAndOperation(UUID paymentId, PaymentOperation operation);
}
