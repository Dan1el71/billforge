package com.billforge.billing.scheduler.job;

import com.billforge.billing.dto.InvoiceResponse;
import com.billforge.billing.entity.Subscription;
import com.billforge.tenant.entity.Tenant;
import com.billforge.shared.enums.auth.Status;
import com.billforge.shared.enums.billing.SubscriptionStatus;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.tenant.service.TenantService;
import com.billforge.billing.service.SubscriptionService;
import com.billforge.billing.service.InvoiceService;
import com.billforge.billing.service.PaymentService;
import com.billforge.billing.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Runs daily at midnight.
 *
 * Finds all TRIALING subscriptions whose trial has ended (trialEnd < now) and:
 * 1. Generates an invoice for the subscription plan
 * 2. Creates a Razorpay payment link
 * 3. Sets status to PAST_DUE (not EXPIRED)
 * 4. Gives tenant 7-day grace period to pay
 *
 * If payment is not received within grace period,
 * SubscriptionExpiryJob will set status to EXPIRED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialExpiryJob implements Job {

    private final TenantService tenantService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting TrialExpiryJob");
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        int expiredCount = 0;
        int invoicesGenerated = 0;

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                List<Subscription> expiredTrials = subscriptionService
                        .findAllSubscriptionsByStatusAndTrialEndBefore(SubscriptionStatus.TRIALING, Instant.now());

                for (Subscription sub : expiredTrials) {
                    try {
                        generateInvoiceAndMarkPastDue(tenant, sub);
                        expiredCount++;
                        invoicesGenerated++;
                        
                        log.info("Trial expired and marked PAST_DUE for tenant {}, subscription {}", 
                                tenant.getSchemaName(), sub.getId());
                    } catch (Exception e) {
                        log.error("Error processing trial expiry for subscription {} in tenant {}: {}",
                                sub.getId(), tenant.getSchemaName(), e.getMessage(), e);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
        log.info("TrialExpiryJob completed - processed {} tenants, marked {} subscriptions PAST_DUE, generated {} invoices",
                tenants.size(), expiredCount, invoicesGenerated);
    }

    private void generateInvoiceAndMarkPastDue(Tenant tenant, Subscription sub) {
        try {
            Instant periodStart = sub.getCurrentPeriodStart() != null 
                    ? sub.getCurrentPeriodStart() 
                    : sub.getTrialStart();
            Instant periodEnd = sub.getCurrentPeriodEnd() != null 
                    ? sub.getCurrentPeriodEnd() 
                    : sub.getTrialEnd();

            InvoiceResponse invoice = invoiceService.generateInvoice(
                    sub.getId(), 
                    periodStart, 
                    periodEnd
            );

            invoiceService.finalizeInvoice(invoice.getId());

            var orderResponse = paymentService.processPayment(invoice.getId());

            log.info("Invoice generated for expired trial - tenantId={}, invoiceId={}, orderId={}",
                    tenant.getId(), invoice.getId(), orderResponse.getOrderId());

        } catch (Exception e) {
            log.error("Failed to generate invoice for expired trial tenantId={}: {}", 
                    tenant.getId(), e.getMessage(), e);
        }

        subscriptionService.markTrialAsPastDue(sub.getId());
    }
}
