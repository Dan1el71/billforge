package com.billforge.billing.gateway;

import com.billforge.billing.dto.PaymentIntentResult;
import com.billforge.billing.dto.PaymentLinkResult;
import com.billforge.billing.dto.RefundResult;

public interface PaymentGatewayPort {

    /**
     * Creates a Stripe PaymentIntent for the given amount.
     *
     * @param amountCents amount in the smallest currency unit (cents for USD)
     * @param currency    ISO currency code
     * @param receipt     idempotency key / internal reference
     */
    PaymentIntentResult createPaymentIntent(long amountCents, String currency, String receipt);

    /**
     * Retrieves an existing PaymentIntent so an abandoned checkout can be
     * reopened without creating a duplicate.
     */
    PaymentIntentResult retrievePaymentIntent(String paymentIntentId);

    /**
     * Retrieves the PaymentIntent and reports whether the money was captured.
     * Captures it first when the intent is in {@code requires_capture}.
     */
    boolean verifyPayment(String paymentIntentId);

    /**
     * Refunds a captured payment.
     *
     * @param paymentIntentId Stripe PaymentIntent ID
     * @param amountCents     amount to refund in the smallest currency unit
     */
    RefundResult refundPayment(String paymentIntentId, long amountCents);

    /**
     * Creates a Stripe customer and returns its ID.
     */
    String createCustomer(String email, String name, String phone);

    /**
     * Creates a hosted Stripe Checkout Session for a business invoice.
     */
    PaymentLinkResult createPaymentLink(
            long amountCents,
            String currency,
            String description,
            String customerEmail,
            String customerName,
            String customerPhone,
            String receipt
    );
}
