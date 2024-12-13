package com.billforge.billing.service;

import com.billforge.billing.repository.UsageRecordRepository;
import com.billforge.shared.enums.billing.UsageMetric;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.tenant.entity.Plan;
import com.billforge.tenant.entity.PlanLimit;
import com.billforge.tenant.repository.PlanLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Calculates the usage overage of a plan for a billing period. API calls are
 * charged in blocks of 1,000 above the included limit; plans without an explicit
 * limit, or with an unlimited one, are never charged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageOverageService {

    private static final long BLOCK_SIZE = 1_000L;

    private final PlanLimitRepository planLimitRepository;
    private final UsageRecordRepository usageRecordRepository;

    @Value("${billing.overage.api-call-unit-price:0.50}")
    private BigDecimal unitPrice = new BigDecimal("0.50");

    public record Overage(BigDecimal amount, long excessCalls, BigDecimal units) {
        public boolean hasCharges() {
            return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
        }
    }

    public Overage calculate(Plan plan, Instant periodStart, Instant periodEnd) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || plan == null || periodStart == null || periodEnd == null) {
            return new Overage(BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        PlanLimit limit = planLimitRepository
                .findByPlanIdAndMetricAndDeletedFalse(plan.getId(), UsageMetric.API_CALLS)
                .orElse(null);

        if (limit == null || Boolean.TRUE.equals(limit.getUnlimited()) || limit.getValue() == null) {
            return new Overage(BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        long used = usageRecordRepository
                .sumCountByTenantAndMetricBetween(tenantId, UsageMetric.API_CALLS, periodStart, periodEnd);

        long excess = used - limit.getValue();
        if (excess <= 0) {
            return new Overage(BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        BigDecimal units = BigDecimal.valueOf(excess)
                .divide(BigDecimal.valueOf(BLOCK_SIZE), 0, RoundingMode.CEILING);
        BigDecimal amount = units.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        log.info("Usage overage - tenantId={}, plan={}, used={}, included={}, excess={}, amount={}",
                tenantId, plan.getCode(), used, limit.getValue(), excess, amount);
        return new Overage(amount, excess, units);
    }
}
