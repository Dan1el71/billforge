package com.billforge.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Hosted checkout data for a business invoice.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentLinkResult {

    /** Stripe Checkout Session ID (cs_...). */
    private String id;

    /** URL the customer opens to pay the invoice. */
    private String url;
}
