package com.ficmart.gateway.payment;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderId(String orderId);

    List<Payment> findByCustomerId(Long customerId, Pageable pageable);

    List<Payment> findByStatusInAndUpdatedAtBefore(List<PaymentStatus> statuses, Instant threshold);
    
}
