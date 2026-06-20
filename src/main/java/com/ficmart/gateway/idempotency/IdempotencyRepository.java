package com.ficmart.gateway.idempotency;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, Long> {

    IdempotencyKey findByCustomerIdAndIdempotencyKey(Long customerId, UUID key);    
    
}
