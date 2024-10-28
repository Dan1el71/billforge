package com.billforge.billing.service;

import com.billforge.billing.dto.PaymentIntentResult;
import com.billforge.billing.dto.PaymentLinkResult;
import com.billforge.billing.dto.PaymentResponse;
import com.billforge.billing.dto.RefundResult;
import com.billforge.billing.dto.VerifyPaymentRequest;
import com.billforge.billing.entity.Invoice;
import com.billforge.billing.entity.Payment;
import com.billforge.billing.event.PaymentCapturedEventPublisher;
import com.billforge.billing.event.outbox.OutboxEventPublisher;
import com.billforge.billing.mapper.PaymentMapper;
import com.billforge.billing.repository.SubscriptionRepository;
import com.billforge.shared.enums.billing.InvoiceStatus;
import com.billforge.shared.enums.billing.PaymentStatus;
import com.billforge.shared.enums.notification.NotificationEvent;
import com.billforge.shared.event.billing.BillingEvent;
import com.billforge.shared.event.billing.PaymentCapturedEvent;
import com.billforge.shared.event.audit.AuditLogEvent;
import com.billforge.shared.enums.audit.AuditAction;
import com.billforge.shared.enums.audit.AuditEntityType;
import com.billforge.shared.exception.PaymentException;
import com.billforge.shared.exception.ResourceException;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.billing.repository.InvoiceRepository;
import com.billforge.billing.repository.PaymentRepository;
import com.billforge.billing.gateway.PaymentGatewayPort;
import com.billforge.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int MAX_RETRY_COUNT = 3;

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final PaymentCapturedEventPublisher paymentCapturedEventPublisher;
    private final PaymentGatewayPort paymentGateway;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SubscriptionRepository subscriptionRepository;
    private final TenantService tenantService;
    private final PaymentMapper paymentMapper;


    // ── Initiate payment ──────────────────────────────────────────────────────

    /**
     * Creates a Stripe PaymentIntent for the given invoice.
     * Idempotent - returns the existing intent if one was already created.
     */
    @Transactional
    public PaymentIntentResult processPayment(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw PaymentException.paymentAlreadyProcessed();
        }

        String idempotencyKey = "pay-" + TenantContext.getTenantId() + "-" + invoiceId;

        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> paymentGateway.retrievePaymentIntent(existing.getStripePaymentIntentId()))
                .orElseGet(() -> createNewPaymentIntent(invoice, idempotencyKey));
    }

    private PaymentIntentResult createNewPaymentIntent(Invoice invoice, String idempotencyKey) {
        long amountInCents = invoice.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        PaymentIntentResult intent = paymentGateway.createPaymentIntent(
                amountInCents, invoice.getCurrency(), idempotencyKey);

        Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(invoice.getTotalAmount())
                .currency(invoice.getCurrency())
                .status(PaymentStatus.PENDING)
                .stripePaymentIntentId(intent.getPaymentIntentId())
                .idempotencyKey(idempotencyKey)
                .retryCount(0)
                .build();

        paymentRepository.save(payment);
        log.info("PaymentIntent created - invoiceId={}, paymentIntentId={}",
                invoice.getId(), intent.getPaymentIntentId());

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_INITIATED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("amount", payment.getAmount().toString(), "currency", payment.getCurrency()))
                .description("Payment initiated: " + payment.getAmount() + " " + payment.getCurrency())
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return intent;
    }

    // ── Verify and capture ────────────────────────────────────────────────────

    /**
     * Verifies the PaymentIntent after Stripe.js confirms it.
     * On success: marks payment SUCCEEDED, marks invoice PAID.
     */
    @Transactional
    public PaymentResponse verifyAndCapturePayment(VerifyPaymentRequest request) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(request.getPaymentIntentId())
                .orElseThrow(() -> ResourceException.notFound("Payment", request.getPaymentIntentId()));

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return paymentMapper.toResponse(payment);
        }

        boolean paid = paymentGateway.verifyPayment(request.getPaymentIntentId());
        if (!paid) {
            throw PaymentException.invalidSignature();
        }

        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setPaidAt(Instant.now());
        paymentRepository.save(payment);

        invoiceService.markInvoicePaid(
                payment.getInvoiceId(),
                request.getPaymentIntentId(),
                Instant.now());

        paymentCapturedEventPublisher.publish(new PaymentCapturedEvent(
                NotificationEvent.PAYMENT_CAPTURED,
                TenantContext.getTenantId(),
                payment.getInvoiceId()));

        log.info("Payment verified and captured - paymentId={}", payment.getId());

        firePaymentEvent(NotificationEvent.PAYMENT_SUCCEEDED, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_COMPLETED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "PENDING"))
                .changesAfter(Map.of("status", "SUCCEEDED", "stripePaymentIntentId", payment.getStripePaymentIntentId()))
                .description("Payment completed: " + payment.getAmount() + " " + payment.getCurrency())
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return paymentMapper.toResponse(payment);
    }

    // ── Webhook handlers (called by StripeWebhookController) ───────────────

    @Transactional
    public void handlePaymentSuccess(String paymentIntentId) {
        paymentRepository.findByStripePaymentIntentId(paymentIntentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                return; // Already processed - idempotent
            }
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
            invoiceService.markInvoicePaid(payment.getInvoiceId(), paymentIntentId, Instant.now());
            log.info("Webhook: payment success processed - paymentIntentId={}", paymentIntentId);
            firePaymentEvent(NotificationEvent.PAYMENT_SUCCEEDED, payment);

            outboxEventPublisher.save(AuditLogEvent.builder()
                    .action(AuditAction.PAYMENT_COMPLETED)
                    .entityType(AuditEntityType.PAYMENT)
                    .entityId(payment.getId())
                    .contextTenantId(TenantContext.getTenantId())
                    .contextTenantName(tenantService.fetchTenantName())
                    .description("Payment completed via webhook: " + payment.getAmount() + " " + payment.getCurrency())
                    .module("BILLING")
                    .build(), "Payment", payment.getId());
        });
    }

    @Transactional
    public void handlePaymentFailure(String paymentIntentId, String failureCode, String failureMessage) {
        paymentRepository.findByStripePaymentIntentId(paymentIntentId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(failureCode);
            payment.setFailureMessage(failureMessage);

            if (payment.getRetryCount() < MAX_RETRY_COUNT) {
                // Schedule retry with exponential backoff: 1h, 6h, 24h
                Duration delay = switch (payment.getRetryCount()) {
                    case 0 -> Duration.ofHours(1);
                    case 1 -> Duration.ofHours(6);
                    default -> Duration.ofHours(24);
                };
                payment.setNextRetryAt(Instant.now().plus(delay));
                log.info("Payment failure - scheduling retry #{} for paymentId={}, delay={}",
                        payment.getRetryCount() + 1, payment.getId(), delay);
            }

            paymentRepository.save(payment);
            firePaymentEvent(NotificationEvent.PAYMENT_FAILED, payment);

            outboxEventPublisher.save(AuditLogEvent.builder()
                    .action(AuditAction.PAYMENT_FAILED)
                    .entityType(AuditEntityType.PAYMENT)
                    .entityId(payment.getId())
                    .contextTenantId(TenantContext.getTenantId())
                    .contextTenantName(tenantService.fetchTenantName())
                    .changesAfter(Map.of("status", "FAILED", "failureCode", failureCode, "failureMessage", failureMessage))
                    .description("Payment failed: " + failureCode + " - " + failureMessage)
                    .module("BILLING")
                    .severity("WARN")
                    .build(), "Payment", payment.getId());
        });
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Transactional
    public PaymentIntentResult retryFailedPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));

        if (payment.getRetryCount() >= MAX_RETRY_COUNT) {
            // Suspend the subscription after max retries
            invoiceRepository.findById(payment.getInvoiceId())
                    .ifPresent(inv -> subscriptionRepository.findById(inv.getSubscriptionId())
                            .orElseThrow(() -> ResourceException.notFound("Subscription", inv.getSubscriptionId())));
            throw ResourceException.invalid("Maximum retry attempts reached. Subscription suspended.");
        }

        long amountInCents = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        String retryReceipt = payment.getIdempotencyKey() + "-retry-" + (payment.getRetryCount() + 1);
        PaymentIntentResult intent = paymentGateway.createPaymentIntent(
                amountInCents, payment.getCurrency(), retryReceipt);

        payment.setStripePaymentIntentId(intent.getPaymentIntentId());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setRetryCount(payment.getRetryCount() + 1);
        payment.setNextRetryAt(null);
        paymentRepository.save(payment);

        log.info("Payment retry #{} created - paymentId={}, paymentIntentId={}",
                payment.getRetryCount(), paymentId, intent.getPaymentIntentId());

        firePaymentEvent(NotificationEvent.PAYMENT_RETRY, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_INITIATED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("Payment retry #" + payment.getRetryCount() + " initiated")
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return intent;
    }

    // ── Refund ────────────────────────────────────────────────────────────────

    @Transactional
    public PaymentResponse refundPayment(Long paymentId, Long amount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw ResourceException.invalid("Only SUCCEEDED payments can be refunded");
        }

        // null or 0 means a full refund, in cents
        long originalAmountCents = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        long refundAmountCents = (amount == null || amount == 0)
                ? originalAmountCents
                : amount * 100;

        RefundResult result = paymentGateway.refundPayment(
                payment.getStripePaymentIntentId(), refundAmountCents);

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        log.info("Payment refunded - paymentId={}, amountCents={}", paymentId, refundAmountCents);
        firePaymentEvent(NotificationEvent.PAYMENT_REFUNDED, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_REFUNDED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "SUCCEEDED"))
                .changesAfter(Map.of("status", "REFUNDED", "refundAmount", String.valueOf(refundAmountCents)))
                .description("Payment refunded: " + (amount != null ? amount : payment.getAmount()) + " " + payment.getCurrency())
                .module("BILLING")
                .severity("WARN")
                .build(), "Payment", payment.getId());

        return paymentMapper.toResponse(payment);
    }

    // ── Payment Link ─────────────────────────────────────────────────────────

    /**
     * Creates a hosted Stripe Checkout Session for an invoice and returns its URL.
     */
    @Transactional
    public String createPaymentLink(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));

        long amountCents = invoice.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        String description = "Invoice: " + invoice.getInvoiceNumber();
        String receipt = invoice.getInvoiceNumber();
        String customerEmail = null;
        String customerName = tenantService.fetchTenantName();
        String customerPhone = null;

        PaymentLinkResult link = paymentGateway.createPaymentLink(
                amountCents,
                invoice.getCurrency(),
                description,
                customerEmail,
                customerName,
                customerPhone,
                receipt
        );

        invoice.setStripePaymentLinkUrl(link.getUrl());
        invoiceRepository.save(invoice);

        log.info("Payment link created for invoice {}: {}", invoiceId, link.getId());

        return link.getUrl();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public PaymentResponse getPaymentById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Payment", id));
        return paymentMapper.toResponse(payment);
    }

    public List<PaymentResponse> getPaymentsByInvoice(Long invoiceId) {
        return paymentRepository.findByInvoiceId(invoiceId)
                .stream()
                .map(paymentMapper::toResponse)
                .collect(Collectors.toList());
    }

    public Page<PaymentResponse> listPayments(Pageable pageable) {
        return paymentRepository.findAll(pageable).map(paymentMapper::toResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────


    private void firePaymentEvent(NotificationEvent eventType, Payment payment) {
        try {
            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(eventType)
                    .paymentId(payment.getStripePaymentIntentId())
                    .paymentAmount(payment.getAmount())
                    .build(), "Payment", payment.getId());
        } catch (Exception e) {
            log.warn("Failed to fire {} event for paymentId={}: {}", eventType, payment.getId(), e.getMessage());
        }
    }
}
