package com.billforge.billing.dto.subscription;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.billforge.shared.enums.billing.BillingCycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Returned by POST /api/subscriptions/upgrade/pro
 * and POST /api/subscriptions/upgrade/enterprise
 * when payment is required.
 *
 * The frontend confirms the PaymentIntent with Stripe.js using the client
 * secret and publishable key, then calls POST /api/payments/verify with the
 * PaymentIntent ID.
 *
 * STATE MACHINE:
 *   After this response is returned, the subscription has a pending invoice
 *   (status=OPEN, linked via upgradePendingInvoiceId). The subscription plan
 *   does NOT change yet. It changes only after /api/payments/verify succeeds.
 *
 * IDEMPOTENCY:
 *   If the user abandons the checkout and calls the upgrade endpoint again,
 *   the service returns the existing PaymentIntent for the same invoice
 *   rather than creating a duplicate charge.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubscriptionOrderResponse {

    // ── Stripe checkout data ────────────────────────────────────────────────

    /** Stripe PaymentIntent ID. */
    private String paymentIntentId;

    /** Client secret passed to Stripe.js to confirm the payment. */
    private String clientSecret;

    /** Stripe publishable key used to initialise Stripe.js. */
    private String publishableKey;

    /** Amount in cents. */
    private Long amountCents;

    /** Currency code, for example "USD". */
    private String currency;

    /** Human-readable description shown by the payment form. */
    private String description;

    // ── Invoice context ───────────────────────────────────────────────────────

    /**
     * Internal invoice ID. The backend also stores it on the subscription
     * (upgradePendingInvoiceId) so it can be resolved without the frontend
     * sending it back.
     */
    private Long invoiceId;

    /** Invoice number (e.g. INV-1-202603-0001) - for display in UI. */
    private String invoiceNumber;

    // ── Upgrade summary ───────────────────────────────────────────────────────

    /** Target plan name - "Pro" or "Enterprise". */
    private String targetPlanName;

    /** Selected billing cycle. */
    private BillingCycle billingCycle;

    /** Charge amount as a formatted decimal (for display only). */
    private BigDecimal chargeAmount;

    /** Proration credit applied (null if no proration). */
    private BigDecimal prorationCredit;

    // ── Post-payment preview ──────────────────────────────────────────────────

    /**
     * What the new billing period will be after successful payment.
     * The frontend can show "Your new plan runs from {newPeriodStart} to {newPeriodEnd}"
     * on a confirmation screen.
     */
    private Instant newPeriodStart;
    private Instant newPeriodEnd;
}
