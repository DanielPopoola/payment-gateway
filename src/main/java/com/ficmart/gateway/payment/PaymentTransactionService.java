package com.ficmart.gateway.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;

@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentTransactionService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional
    public Payment authorizePhaseOne(AuthorizeRequest request, UUID idempotencyKey) {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();

        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setOrderId(request.orderId());
        payment.setCustomerId(request.customerId());
        payment.setAmountCents(request.amountCents());
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        paymentRepository.save(payment);

        saveEvent(paymentId, idempotencyKey, PaymentEventType.AUTHORIZATION_REQUESTED);

        return payment;
    }

    @Transactional
    public Payment authorizePhaseTwoSuccess(Payment payment, BankAuthorizationResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setBankAuthId(bankResponse.authorizationId());
        payment.setExpiresAt(bankResponse.expiresAt());
        payment.setAuthorizedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_SUCCEEDED);

        return payment;
    }

    @Transactional
    public void authorizePhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.AUTHORIZATION_FAILED);
    }

    @Transactional
    public Payment capturePhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found",
                HttpStatus.NOT_FOUND, "payment_not_found", null));

        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.CAPTURING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_REQUESTED);

        return payment;
    }

    @Transactional
    public Payment capturePhaseTwoSuccess(Payment payment, BankCaptureResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setBankCaptureId(bankResponse.captureId());
        payment.setCaptureAt(bankResponse.capturedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_SUCCEEDED);
    
        return payment;
    }
    
    @Transactional
    public void capturePhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.CAPTURE_FAILED);
    }

    @Transactional
    public Payment voidPhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));
        
        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.VOIDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_REQUESTED);

        return payment;
    }

    @Transactional
    public Payment voidPhaseTwoSuccess(Payment payment, BankVoidResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.VOIDED);
        payment.setBankVoidId(bankResponse.voidId());
        payment.setVoidedAt(bankResponse.voidedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_SUCCEEDED);
    
        return payment;
    }

    @Transactional
    public void voidPhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.VOID_FAILED);
    }

    @Transactional
    public Payment refundPhaseOne(UUID paymentId, UUID idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new GatewayException("Payment not found", 
                HttpStatus.NOT_FOUND, "payment_not_found", null));
        
        payment.setStatus(payment.getStatus().transitionTo(PaymentStatus.REFUNDING));
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_REQUESTED);

        return payment;
    }

    @Transactional
    public Payment refundPhaseTwoSuccess(Payment payment, BankRefundResponse bankResponse, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setBankRefundId(bankResponse.refundId());
        payment.setRefundedAt(bankResponse.refundedAt());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_SUCCEEDED);
    
        return payment;
    }

    @Transactional
    public void refundPhaseTwoFailure(Payment payment, GatewayException ex, UUID idempotencyKey) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveEvent(payment.getId(), idempotencyKey, PaymentEventType.REFUND_FAILED);
    }

    private void saveEvent(UUID paymentId, UUID idempotencyKey, PaymentEventType eventType) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(paymentId);
        event.setIdempotencyKey(idempotencyKey);
        event.setEventType(eventType);
        event.setCreatedAt(Instant.now());
        paymentEventRepository.save(event);
    }
}