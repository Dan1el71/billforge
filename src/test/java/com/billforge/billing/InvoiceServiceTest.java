package com.billforge.billing;

import com.billforge.app.BillforgeApplication;
import com.billforge.billing.dto.InvoiceResponse;
import com.billforge.billing.entity.Invoice;
import com.billforge.billing.entity.InvoiceLineItem;
import com.billforge.billing.entity.Subscription;
import com.billforge.billing.repository.InvoiceLineItemRepository;
import com.billforge.billing.repository.InvoiceRepository;
import com.billforge.billing.service.InvoiceService;
import com.billforge.shared.enums.billing.BillingCycle;
import com.billforge.shared.enums.billing.InvoiceStatus;
import com.billforge.shared.enums.billing.LineItemType;
import com.billforge.shared.enums.billing.SubscriptionStatus;
import com.billforge.shared.exception.ResourceException;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.support.TestDataBuilder;
import com.billforge.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = BillforgeApplication.class)
@ActiveProfiles("test")
@DisplayName("InvoiceService Integration Tests")
class InvoiceServiceTest {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceLineItemRepository lineItemRepository;

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
    @DisplayName("generateInvoice")
    class GenerateInvoiceTests {

        @Test
        @DisplayName("generateInvoice creates draft with correct amounts")
        void generateInvoice_createsDraftWithCorrectAmounts() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .billingCycle(BillingCycle.MONTHLY)
                .build();

            Instant periodStart = Instant.now();
            Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS);

            InvoiceResponse response = invoiceService.generateInvoice(sub.getId(), periodStart, periodEnd);

            assertNotNull(response);
            assertEquals(InvoiceStatus.DRAFT, response.getStatus());
            assertEquals(0, new BigDecimal("999").compareTo(response.getSubtotal()));
            assertEquals(0, new BigDecimal("999").compareTo(response.getTotalAmount()));
            assertEquals("INR", response.getCurrency());
            assertNotNull(response.getLineItems());
            assertFalse(response.getLineItems().isEmpty());
        }

        @Test
        @DisplayName("generateInvoice throws when subscription not found")
        void generateInvoice_subscriptionNotFound_throws() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            assertThrows(ResourceException.class, () ->
                invoiceService.generateInvoice(99999L, Instant.now(), Instant.now().plusSeconds(30))
            );
        }
    }

    @Nested
    @DisplayName("finalizeInvoice")
    class FinalizeInvoiceTests {

        @Test
        @DisplayName("finalizeInvoice draft becomes open with due date set")
        void finalizeInvoice_draftBecomesOpen_dueDateSet() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            InvoiceResponse invoice = invoiceService.generateInvoice(
                sub.getId(),
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS)
            );

            InvoiceResponse finalized = invoiceService.finalizeInvoice(invoice.getId());

            assertEquals(InvoiceStatus.OPEN, finalized.getStatus());
            assertNotNull(finalized.getDueDate());
        }

        @Test
        @DisplayName("finalizeInvoice empty line items throws ResourceException")
        void finalizeInvoice_emptyLineItems_throwsResourceException() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice savedInvoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-EMPTY-001")
                .status(InvoiceStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .currency("INR")
                .build());

            assertThrows(ResourceException.class, () ->
                invoiceService.finalizeInvoice(savedInvoice.getId())
            );
        }

        @Test
        @DisplayName("finalizeInvoice non-draft throws ResourceException")
        void finalizeInvoice_nonDraft_throwsResourceException() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice savedInvoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-OPEN-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build());

            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(savedInvoice)
                .description("Test item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("999"))
                .totalPrice(new BigDecimal("999"))
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();
            lineItemRepository.save(lineItem);

            assertThrows(ResourceException.class, () ->
                invoiceService.finalizeInvoice(savedInvoice.getId())
            );
        }
    }

    @Nested
    @DisplayName("markInvoicePaid")
    class MarkInvoicePaidTests {

        @Test
        @DisplayName("markInvoicePaid open invoice becomes paid")
        void markInvoicePaid_openInvoice_becomesPaid() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-PAID-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(invoice)
                .description("Test item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("999"))
                .totalPrice(new BigDecimal("999"))
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();
            lineItemRepository.save(lineItem);

            invoiceService.markInvoicePaid(invoice.getId(), "pay_test_123", Instant.now());

            Invoice updated = invoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updated.getStatus());
            assertNotNull(updated.getPaidAt());
            assertEquals("pay_test_123", updated.getRazorpayInvoiceId());
        }
    }

    @Nested
    @DisplayName("voidInvoice")
    class VoidInvoiceTests {

        @Test
        @DisplayName("voidInvoice open invoice becomes void")
        void voidInvoice_openInvoice_becomesVoid() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-VOID-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(invoice)
                .description("Test item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("999"))
                .totalPrice(new BigDecimal("999"))
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();
            lineItemRepository.save(lineItem);

            InvoiceResponse voided = invoiceService.voidInvoice(invoice.getId());

            assertEquals(InvoiceStatus.VOID, voided.getStatus());
        }

        @Test
        @DisplayName("voidInvoice draft invoice becomes void")
        void voidInvoice_draft_becomesVoid() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-VOID-DRAFT-001")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(invoice)
                .description("Test item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("999"))
                .totalPrice(new BigDecimal("999"))
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();
            lineItemRepository.save(lineItem);

            InvoiceResponse voided = invoiceService.voidInvoice(invoice.getId());

            assertEquals(InvoiceStatus.VOID, voided.getStatus());
        }

        @Test
        @DisplayName("voidInvoice paid invoice throws ResourceException")
        void voidInvoice_paidInvoice_throwsResourceException() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice savedInvoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-PAID-VOID-001")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build());

            assertThrows(ResourceException.class, () ->
                invoiceService.voidInvoice(savedInvoice.getId())
            );
        }
    }

    @Nested
    @DisplayName("getInvoiceById")
    class GetInvoiceTests {

        @Test
        @DisplayName("getInvoiceById returns invoice with line items")
        void getInvoiceById_returnsInvoiceWithLineItems() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-GET-001")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(invoice)
                .description("Test item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("999"))
                .totalPrice(new BigDecimal("999"))
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();
            lineItemRepository.save(lineItem);

            InvoiceResponse response = invoiceService.getInvoiceById(invoice.getId());

            assertNotNull(response);
            assertEquals("INV-GET-001", response.getInvoiceNumber());
            assertEquals(1, response.getLineItems().size());
        }

        @Test
        @DisplayName("getInvoiceById throws when not found")
        void getInvoiceById_notFound_throws() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            assertThrows(ResourceException.class, () ->
                invoiceService.getInvoiceById(99999L)
            );
        }
    }
}