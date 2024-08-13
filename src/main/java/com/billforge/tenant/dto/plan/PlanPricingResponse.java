package com.billforge.tenant.dto.plan;

import com.billforge.shared.enums.billing.BillingCycle;
import com.billforge.shared.enums.billing.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for plan pricing (one pricing row per billing cycle).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPricingResponse {

    private Long id;

    private BillingCycle billingCycle;

    private BigDecimal price;

    private Currency currency;

    private Integer trialDays;

    private Boolean isDefault;
}
