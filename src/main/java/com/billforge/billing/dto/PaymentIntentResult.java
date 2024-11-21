package com.billforge.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PaymentIntent data returned to the frontend so Stripe.js can confirm the
 * payment without exposing the secret key.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentResult {

    /** Stripe PaymentIntent ID (pi_...). */
    private String paymentIntentId;

    /** Client secret used by Stripe.js to confirm the payment. */
    private String clientSecret;

    /** Amount in the smallest currency unit (cents for USD). */
    private long amount;

    private String currency;

    /** Internal idempotency key used to create the intent. */
    private String receipt;

    private String status;

    /** Publishable key the browser needs to initialise Stripe.js. */
    private String publishableKey;
}
