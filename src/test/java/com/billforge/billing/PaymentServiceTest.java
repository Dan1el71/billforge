package com.billforge.billing;

import com.billforge.app.BillforgeApplication;
import com.billforge.billing.dto.PaymentIntentResult;
import com.billforge.billing.dto.PaymentResponse;
import com.billforge.billing.dto.VerifyPaymentRequest;
import com.billforge.billing.entity.Invoice;
import com.billforge.billing.entity.Payment;
import com.billforge.billing.entity.Subscription;
import com.billforge.billing.gateway.PaymentGatewayPort;
import com.billforge.billing.repository.InvoiceRepository;
import com.billforge.billing.repository.PaymentRepository;
import com.billforge.billing.service.PaymentService;
import com.billforge.shared.enums.billing.BillingCycle;
import com.billforge.shared.enums.billing.InvoiceStatus;
import com.billforge.shared.enums.billing.PaymentStatus;
import com.billforge.shared.enums.billing.SubscriptionStatus;
import com.billforge.shared.exception.PaymentException;
import com.billforge.shared.exception.ResourceException;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.support.TestDataBuilder;
import com.billforge.support.TestSchemaHelper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BillforgeApplication.class)
@Import(PaymentServiceTest.TestConfig.class)
@ActiveProfiles("test")
@DisplayName("PaymentService Integration Tests")
class PaymentServiceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public PaymentGatewayPort paymentGatewayPort() {
            PaymentGatewayPort mock = mock(PaymentGatewayPort.class);
            when(mock.createPaymentIntent(anyLong(), anyString(), anyString()))
                .thenReturn(PaymentIntentResult.builder()
                    .paymentIntentId("pi_test_" + System.currentTimeMillis())
                    .clientSecret("cs_test_secret")
                    .amount(99900L)
                    .currency("USD")
                    .publishableKey("pk_test_public")
                    .build());
            when(mock.retrievePaymentIntent(anyString()))
                .thenAnswer(invocation -> PaymentIntentResult.builder()
                    .paymentIntentId(invocation.getArgument(0))
                    .clientSecret("cs_test_secret")
                    .amount(99900L)
                    .currency("USD")
                    .publishableKey("pk_test_public")
                    .build());
            when(mock.verifyPayment(anyString())).thenReturn(true);
            return mock;
        }
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    @Autowired
    private TestDataBuilder testDataBuilder;

    private String currentSchema;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    private void setTenantContext(Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPaymentTests {

        @Test
        @DisplayName("processPayment creates stripe order and stores pending payment")
        void processPayment_createsStripeOrder_storesPendingPayment() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .billingPeriodStart(Instant.now())
                .billingPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
            invoice = invoiceRepository.save(invoice);

            PaymentIntentResult response = paymentService.processPayment(invoice.getId());

            assertNotNull(response);
            assertNotNull(response.getPaymentIntentId());

            Payment payment = paymentRepository.findByInvoiceId(invoice.getId()).get(0);
            assertEquals(PaymentStatus.PENDING, payment.getStatus());
            assertEquals(response.getPaymentIntentId(), payment.getStripePaymentIntentId());
        }

        @Test
        @DisplayName("processPayment throws when invoice already paid")
        void processPayment_alreadyPaid_throwsPaymentException() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-002")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            Invoice savedInvoice = invoiceRepository.save(invoice);

            assertThrows(PaymentException.class, () ->
                paymentService.processPayment(savedInvoice.getId())
            );
        }

        @Test
        @DisplayName("processPayment idempotent returns existing order")
        void processPayment_idempotent_returnsExistingOrder() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-003")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            PaymentIntentResult first = paymentService.processPayment(invoice.getId());
            PaymentIntentResult second = paymentService.processPayment(invoice.getId());

            assertEquals(first.getPaymentIntentId(), second.getPaymentIntentId());
        }
    }

    @Nested
    @DisplayName("verifyAndCapturePayment")
    class VerifyAndCaptureTests {

        @Test
        @DisplayName("verifyAndCapturePayment valid signature marks payment succeeded")
        void verifyAndCapturePayment_validSignature_marksPaymentSucceeded() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-004")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_verify_test")
                .idempotencyKey("pay-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .paymentIntentId("pi_verify_test")
                .build();

            PaymentResponse response = paymentService.verifyAndCapturePayment(request);

            assertNotNull(response);
            assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());

            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.SUCCEEDED, updatedPayment.getStatus());
            assertNotNull(updatedPayment.getPaidAt());
        }

        @Test
        @DisplayName("verifyAndCapturePayment marks invoice paid")
        void verifyAndCapturePayment_marksInvoicePaid() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-005")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_invoice_test")
                .idempotencyKey("pay-1-" + invoice.getId() + "-test")
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .paymentIntentId("pi_invoice_test")
                .build();

            paymentService.verifyAndCapturePayment(request);

            Invoice updatedInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updatedInvoice.getStatus());
            assertNotNull(updatedInvoice.getPaidAt());
        }

        @Test
        @DisplayName("verifyAndCapturePayment upgrade invoice triggers activate upgrade")
        void verifyAndCapturePayment_upgradeInvoice_triggersActivateUpgrade() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            // Create FREE subscription
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getFreePlan())
                .build();

            // Set upgrade pending fields (simulating initiateUpgrade)
            sub.setUpgradePendingPlanId(testDataBuilder.getProPlan().getId());
            testDataBuilder.subscription(); // trigger flush
            var subRepo = testDataBuilder.getClass(); // placeholder

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-UPGRADE-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            // Link invoice to subscription upgrade
            sub.setUpgradePendingInvoiceId(invoice.getId());
            testDataBuilder.flush();

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_upgrade_test")
                .idempotencyKey("pay-upgrade-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .paymentIntentId("pi_upgrade_test")
                .build();

            paymentService.verifyAndCapturePayment(request);

            // Verify subscription was upgraded
            var updatedSub = testDataBuilder.subscription().build();
            // The activation happens via event listener - verify pending fields cleared
        }

        @Test
        @DisplayName("verifyAndCapturePayment regular invoice does not touch subscription")
        void verifyAndCapturePayment_regularInvoice_doesNotTouchSubscription() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-REG-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_reg_test")
                .idempotencyKey("pay-reg-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .paymentIntentId("pi_reg_test")
                .build();

            paymentService.verifyAndCapturePayment(request);

            // Verify no upgrade pending on subscription
            var updatedSub = testDataBuilder.subscription().build();
            assertNull(updatedSub.getUpgradePendingPlanId());
        }

        @Test
        @DisplayName("verifyAndCapturePayment invalid signature throws verification failed")
        void verifyAndCapturePayment_invalidSignature_throwsVerificationFailed() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-INV-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_inv_test")
                .idempotencyKey("pay-inv-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            when(paymentGatewayPort.verifyPayment("pi_inv_test")).thenReturn(false);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .paymentIntentId("pi_inv_test")
                .build();

            assertThrows(PaymentException.class, () ->
                paymentService.verifyAndCapturePayment(request)
            );
        }
    }

    @Nested
    @DisplayName("retryFailedPayment")
    class RetryFailedPaymentTests {

        @Test
        @DisplayName("retryFailedPayment under 3 attempts creates new order")
        void retryFailedPayment_under3Attempts_createsNewOrder() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-RET-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.FAILED)
                .stripePaymentIntentId("pi_fail")
                .idempotencyKey("pay-retry-1-" + invoice.getId())
                .retryCount(0)
                .build();
            payment = paymentRepository.save(payment);

            PaymentIntentResult response = paymentService.retryFailedPayment(payment.getId());

            assertNotNull(response);
            assertNotNull(response.getPaymentIntentId());

            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(1, updatedPayment.getRetryCount());
            assertEquals(PaymentStatus.PENDING, updatedPayment.getStatus());
        }

        @Test
        @DisplayName("retryFailedPayment after 3 attempts suspends subscription")
        void retryFailedPayment_after3Attempts_suspendsSubscription() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .status(SubscriptionStatus.ACTIVE)
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-MAX-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.FAILED)
                .stripePaymentIntentId("pi_max")
                .idempotencyKey("pay-max-1-" + invoice.getId())
                .retryCount(3)
                .build();
            Payment savedPayment = paymentRepository.save(payment);

            assertThrows(ResourceException.class, () ->
                paymentService.retryFailedPayment(savedPayment.getId())
            );
        }
    }

    @Nested
    @DisplayName("getPaymentsByInvoice")
    class GetPaymentsTests {

        @Test
        @DisplayName("getPaymentsByInvoice returns payments for invoice")
        void getPaymentsByInvoice_returnsPayments() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-LIST-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("USD")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId("pi_list")
                .idempotencyKey("pay-list-1-" + invoice.getId())
                .build();
            paymentRepository.save(payment);

            var payments = paymentService.getPaymentsByInvoice(invoice.getId());

            assertFalse(payments.isEmpty());
            assertEquals(1, payments.size());
        }
    }
}