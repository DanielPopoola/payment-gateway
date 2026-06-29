package com.ficmart.gateway.payment;

import com.ficmart.gateway.bank.*;
import com.ficmart.gateway.common.GatewayException;
import com.ficmart.gateway.common.InvalidTransitionException;
import com.ficmart.gateway.idempotency.IdempotencyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.Transactional;

import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class PaymentServiceIntegrationTest {


    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;
    @Autowired private FakeBankClient fakeBankClient;

    @BeforeEach
    void resetFake() {
        fakeBankClient.reset();
    }

    @BeforeEach
    void cleanDb() {
        paymentEventRepository.deleteAll();
        idempotencyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @TestConfiguration
    static class FakeBankConfig {

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

    // --- Happy path lifecycles ---

    @Test
    @Transactional
    void authorizeCapureRefund_fullLifecycle() {
        Payment authorized = authorizePayment();
        assertEquals(PaymentStatus.AUTHORIZED, authorized.getStatus());
        assertNotNull(authorized.getBankAuthId());
        assertNotNull(authorized.getAuthorizedAt());
        assertNotNull(authorized.getExpiresAt());

        Payment captured = paymentService.capture(authorized.getId(), UUID.randomUUID());
        assertEquals(PaymentStatus.CAPTURED, captured.getStatus());
        assertNotNull(captured.getBankCaptureId());
        assertNotNull(captured.getCapturedAt());

        Payment refunded = paymentService.refund(captured.getId(), UUID.randomUUID());
        assertEquals(PaymentStatus.REFUNDED, refunded.getStatus());
        assertNotNull(refunded.getBankRefundId());
        assertNotNull(refunded.getRefundedAt());
    }

    @Test
    @Transactional
    void authorizeVoid_fullLifecycle() {
        Payment authorized = authorizePayment();

        Payment voided = paymentService.void_(authorized.getId(), UUID.randomUUID());
        assertEquals(PaymentStatus.VOIDED, voided.getStatus());
        assertNotNull(voided.getBankVoidId());
        assertNotNull(voided.getVoidedAt());
    }

    // --- Idempotency correctness ---

    @Test
    @Transactional
    void authorize_sameKeyTwice_returnsIdenticalResponse() {
        UUID idempotencyKey = UUID.randomUUID();

        Payment first = paymentService.authorize(validRequest(), idempotencyKey);
        Payment second = paymentService.authorize(validRequest(), idempotencyKey);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, fakeBankClient.authorizeCallCount());
        assertEquals(1, paymentRepository.findAll().size());
    }

    // --- State machine enforcement ---

    @Test
    @Transactional
    void captureVoidedPayment_returns409() {
        Payment authorized = authorizePayment();
        paymentService.void_(authorized.getId(), UUID.randomUUID());

        assertThrows(InvalidTransitionException.class, () ->
            paymentService.capture(authorized.getId(), UUID.randomUUID()));

        assertEquals(PaymentStatus.VOIDED,
            paymentRepository.findById(authorized.getId()).get().getStatus());
    }

    @Test
    @Transactional
    void voidCapturedPayment_returns409() {
        Payment authorized = authorizePayment();
        paymentService.capture(authorized.getId(), UUID.randomUUID());

        assertThrows(InvalidTransitionException.class, () ->
            paymentService.void_(authorized.getId(), UUID.randomUUID()));

        assertEquals(PaymentStatus.CAPTURED,
            paymentRepository.findById(authorized.getId()).get().getStatus());
    }

    @Test
    @Transactional
    void refundAuthorizedPayment_returns409() {
        Payment authorized = authorizePayment();

        assertThrows(InvalidTransitionException.class, () ->
            paymentService.refund(authorized.getId(), UUID.randomUUID()));

        assertEquals(PaymentStatus.AUTHORIZED,
            paymentRepository.findById(authorized.getId()).get().getStatus());
    }

    @Test
    @Transactional
    void capturePendingPayment_returns409() {
        Payment payment = paymentService.authorize(validRequest(), UUID.randomUUID());

        // manually force PENDING so we can test the transition
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment);

        assertThrows(InvalidTransitionException.class, () ->
            paymentService.capture(payment.getId(), UUID.randomUUID()));
    }

    // --- Concurrent competing operations ---

    @Test
    void concurrentCaptureAndVoid_onlyOneSucceeds() throws Exception {
        Payment authorized = authorizePayment();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> captureError = new AtomicReference<>();
        AtomicReference<Exception> voidError = new AtomicReference<>();

        Thread captureThread = new Thread(() -> {
            try {
                latch.await();
                paymentService.capture(authorized.getId(), UUID.randomUUID());
            } catch (Exception ex) {
                captureError.set(ex);
            }
        });

        Thread voidThread = new Thread(() -> {
            try {
                latch.await();
                paymentService.void_(authorized.getId(), UUID.randomUUID());
            } catch (Exception ex) {
                voidError.set(ex);
            }
        });

        captureThread.start();
        voidThread.start();
        latch.countDown();
        captureThread.join();
        voidThread.join();

        boolean oneFailed = (captureError.get() != null) ^ (voidError.get() != null);
        assertTrue(oneFailed);

        Payment finalPayment = paymentService.getPayment(authorized.getId());
        assertTrue(finalPayment.getStatus() == PaymentStatus.CAPTURED
            || finalPayment.getStatus() == PaymentStatus.VOIDED);
    }

    // --- Failure correctness ---

    @Test
    @Transactional
    void authorizePermanentFailure_setsFailedStatus() {
        fakeBankClient.failNextAuthorize();

        GatewayException ex = assertThrows(GatewayException.class, () ->
            paymentService.authorize(validRequest(), UUID.randomUUID()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());

        List<Payment> payments = paymentRepository.findAll();
        assertEquals(1, payments.size());
        assertEquals(PaymentStatus.FAILED, payments.get(0).getStatus());
        assertNotNull(payments.get(0).getFailedAt());
    }

    @Test
    @Transactional
    void capturePermanentFailure_setsFailedStatus() {
        Payment authorized = authorizePayment();
        fakeBankClient.failNextCapture();

        assertThrows(GatewayException.class, () ->
            paymentService.capture(authorized.getId(), UUID.randomUUID()));

        assertEquals(PaymentStatus.FAILED,
            paymentRepository.findById(authorized.getId()).get().getStatus());
    }

    // --- Audit log correctness ---

    @Test
    @Transactional
    void authorizeCapture_produces4EventsInOrder() {
        Payment authorized = authorizePayment();
        paymentService.capture(authorized.getId(), UUID.randomUUID());

        List<PaymentEvent> events = paymentEventRepository
            .findByPaymentIdOrderByCreatedAtAsc(authorized.getId());

        assertEquals(4, events.size());
        assertEquals(PaymentEventType.AUTHORIZATION_REQUESTED, events.get(0).getEventType());
        assertEquals(PaymentEventType.AUTHORIZATION_SUCCEEDED, events.get(1).getEventType());
        assertEquals(PaymentEventType.CAPTURE_REQUESTED,       events.get(2).getEventType());
        assertEquals(PaymentEventType.CAPTURE_SUCCEEDED,       events.get(3).getEventType());
    }

    // --- Helpers ---

    private Payment authorizePayment() {
        return paymentService.authorize(validRequest(), UUID.randomUUID());
    }

    private AuthorizeRequest validRequest() {
        return new AuthorizeRequest(
            "4111111111111111", "123", 12, 2030, 5000L, "order_1", 42L);
    }

}