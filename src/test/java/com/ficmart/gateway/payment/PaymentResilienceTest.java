package com.ficmart.gateway.payment;

import com.ficmart.gateway.bank.FakeBankClient;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.idempotency.IdempotencyKey;
import com.ficmart.gateway.idempotency.IdempotencyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class PaymentResilienceTest {

    @TestConfiguration
    static class Config {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Primary
        @Bean(name = "retryingBankClient")
        public FakeBankClient fakeBankClient() {
            return new FakeBankClient();
        }
    }

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;
    @Autowired private ReconciliationWorker reconciliationWorker;
    @Autowired private FakeBankClient fakeBankClient;

    @BeforeEach
    void setup() {
        paymentEventRepository.deleteAll();
        idempotencyRepository.deleteAll();
        paymentRepository.deleteAll();
        fakeBankClient.reset();
    }

    // --- Retry under chaos ---

    @Test
    void fullLifecycle_completesEventuallyDespiteTransientFailures() {
        fakeBankClient.failNextAuthorize();
        fakeBankClient.reset();

        Payment authorized = authorizePayment();
        assertEquals(PaymentStatus.AUTHORIZED, authorized.getStatus());
        assertNotNull(authorized.getBankAuthId());

        Payment captured = paymentService.capture(authorized.getId(), UUID.randomUUID());
        assertEquals(PaymentStatus.CAPTURED, captured.getStatus());

        Payment refunded = paymentService.refund(captured.getId(), UUID.randomUUID());
        assertEquals(PaymentStatus.REFUNDED, refunded.getStatus());
    }

    @Test
    void capture_noDoubleCharge_onIdempotentReplay() {
        Payment authorized = authorizePayment();
        UUID captureKey = UUID.randomUUID();

        Payment cap1 = paymentService.capture(authorized.getId(), captureKey);
        Payment cap2 = paymentService.capture(authorized.getId(), captureKey);

        assertEquals(cap1.getBankCaptureId(), cap2.getBankCaptureId());

        long captureSucceededCount = paymentEventRepository
            .findByPaymentIdOrderByCreatedAtAsc(authorized.getId())
            .stream()
            .filter(e -> e.getEventType() == PaymentEventType.CAPTURE_SUCCEEDED)
            .count();
        assertEquals(1, captureSucceededCount);
    }

    // --- Reconciliation worker recovery ---

    @Test
    void reconciliationWorker_recoversStuckCapturing() {
        Payment authorized = authorizePayment();

        authorized.setStatus(PaymentStatus.CAPTURING);
        authorized.setUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        paymentRepository.save(authorized);

        IdempotencyKey captureKey = new IdempotencyKey();
        captureKey.setPaymentId(authorized.getId());
        captureKey.setOperation(PaymentOperation.CAPTURE);
        captureKey.setRequestHash("test-hash");
        captureKey.setIdempotencyKey(UUID.randomUUID());
        captureKey.setLockedAt(Instant.now());
        captureKey.setLastRunAt(Instant.now());
        captureKey.setCreatedAt(Instant.now());
        idempotencyRepository.save(captureKey);

        reconciliationWorker.run();

        Payment recovered = paymentRepository.findById(authorized.getId()).get();
        assertEquals(PaymentStatus.CAPTURED, recovered.getStatus());
        assertNotNull(recovered.getBankCaptureId());
        assertNull(idempotencyRepository.findByPaymentIdAndOperation(authorized.getId(), PaymentOperation.CAPTURE).getLockedAt());
    }

    @Test
    void reconciliationWorker_recoversStuckVoiding() {
        Payment authorized = authorizePayment();

        authorized.setStatus(PaymentStatus.VOIDING);
        authorized.setUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        paymentRepository.save(authorized);

        IdempotencyKey voidKey = new IdempotencyKey();
        voidKey.setPaymentId(authorized.getId());
        voidKey.setOperation(PaymentOperation.VOID);
        voidKey.setRequestHash("test-hash");
        voidKey.setIdempotencyKey(UUID.randomUUID());
        voidKey.setLockedAt(Instant.now());
        voidKey.setLastRunAt(Instant.now());
        voidKey.setCreatedAt(Instant.now());
        idempotencyRepository.save(voidKey);


        reconciliationWorker.run();

        Payment recovered = paymentRepository.findById(authorized.getId()).get();
        assertEquals(PaymentStatus.VOIDED, recovered.getStatus());
        assertNotNull(recovered.getBankVoidId());
    }

    @Test
    void reconciliationWorker_recoversStuckRefunding() {
        Payment authorized = authorizePayment();
        Payment captured = capturePayment(authorized.getId());

        captured.setStatus(PaymentStatus.REFUNDING);
        captured.setUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        paymentRepository.save(captured);

        IdempotencyKey refundKey = new IdempotencyKey();
        refundKey.setPaymentId(authorized.getId());
        refundKey.setOperation(PaymentOperation.REFUND);
        refundKey.setRequestHash("test-hash");
        refundKey.setIdempotencyKey(UUID.randomUUID());
        refundKey.setLockedAt(Instant.now());
        refundKey.setLastRunAt(Instant.now());
        refundKey.setCreatedAt(Instant.now());
        idempotencyRepository.save(refundKey);


        reconciliationWorker.run();

        Payment recovered = paymentRepository.findById(authorized.getId()).get();
        assertEquals(PaymentStatus.REFUNDED, recovered.getStatus());
        assertNotNull(recovered.getBankRefundId());
    }

    @Test
    void reconciliationWorker_alertsStuckPending_noDbMutation() {
        Payment authorized = authorizePayment();

        authorized.setStatus(PaymentStatus.PENDING);
        authorized.setUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        paymentRepository.save(authorized);

        reconciliationWorker.run();

        Payment unchanged = paymentRepository.findById(authorized.getId()).get();
        assertEquals(PaymentStatus.PENDING, unchanged.getStatus());
    }

    // --- Permanent failure fast-path ---

    @Test
    void authorize_permanentFailure_failsImmediatelyWithNoRetry() {
        fakeBankClient.failNextAuthorize();

        assertThrows(GatewayException.class, () ->
            paymentService.authorize(validRequest(), UUID.randomUUID()));

        assertEquals(1, fakeBankClient.authorizeCallCount());

        Payment stored = paymentRepository.findAll().get(0);
        assertEquals(PaymentStatus.FAILED, stored.getStatus());
        assertNotNull(stored.getFailedAt());
    }

    @Test
    void capture_permanentFailure_setsFailedStatus() {
        Payment authorized = authorizePayment();
        fakeBankClient.failNextCapture();

        assertThrows(GatewayException.class, () ->
            paymentService.capture(authorized.getId(), UUID.randomUUID()));

        assertEquals(PaymentStatus.FAILED,
            paymentRepository.findById(authorized.getId()).get().getStatus());
    }

    // --- Helpers ---

    private Payment authorizePayment() {
        return paymentService.authorize(validRequest(), UUID.randomUUID());
    }

    private Payment capturePayment(UUID paymentId) {
        paymentService.capture(paymentId, UUID.randomUUID());
        return paymentRepository.findById(paymentId).get();
    }

    private AuthorizeRequest validRequest() {
        return new AuthorizeRequest(
            "4111111111111111", "123", 12, 2030, 5000L, "order_1", 42L);
    }
}