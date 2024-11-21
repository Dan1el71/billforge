package com.billforge.tenant.dto.onboarding;

import com.billforge.shared.enums.billing.BillingCycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingStep3PaymentResponse {

    private boolean paymentRequired;
    private Long tenantId;
    private Long planId;
    private String planName;
    private BillingCycle billingCycle;
    private Integer trialDays;
    private boolean onboardingComplete;
    private String message;
    private Integer currentStep;
}