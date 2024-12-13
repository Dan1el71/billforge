package com.billforge.billing;

import com.billforge.billing.repository.UsageRecordRepository;
import com.billforge.billing.service.UsageOverageService;
import com.billforge.shared.enums.billing.UsageMetric;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.tenant.entity.Plan;
import com.billforge.tenant.entity.PlanLimit;
import com.billforge.tenant.repository.PlanLimitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageOverageServiceTest {

    @Mock
    private PlanLimitRepository planLimitRepository;

    @Mock
    private UsageRecordRepository usageRecordRepository;

    @InjectMocks
    private UsageOverageService service;

    private final Plan plan = Plan.builder().code("PRO").name("PRO").displayName("Pro").build();
    private final Instant start = Instant.parse("2024-11-01T00:00:00Z");
    private final Instant end = Instant.parse("2024-12-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void chargesBlocksAboveTheIncludedLimit() {
        when(planLimitRepository.findByPlanIdAndMetricAndDeletedFalse(any(), eq(UsageMetric.API_CALLS)))
                .thenReturn(Optional.of(PlanLimit.builder().value(1_000L).unlimited(false).build()));
        when(usageRecordRepository.sumCountByTenantAndMetricBetween(1L, UsageMetric.API_CALLS, start, end))
                .thenReturn(3_500L);

        var overage = service.calculate(plan, start, end);

        assertTrue(overage.hasCharges());
        assertEquals(2_500L, overage.excessCalls());
        assertEquals(0, new BigDecimal("3").compareTo(overage.units()));
        assertEquals(0, new BigDecimal("1.50").compareTo(overage.amount()));
    }

    @Test
    void doesNotChargeWhenUsageIsWithinTheLimit() {
        when(planLimitRepository.findByPlanIdAndMetricAndDeletedFalse(any(), eq(UsageMetric.API_CALLS)))
                .thenReturn(Optional.of(PlanLimit.builder().value(5_000L).unlimited(false).build()));
        when(usageRecordRepository.sumCountByTenantAndMetricBetween(1L, UsageMetric.API_CALLS, start, end))
                .thenReturn(4_000L);

        assertFalse(service.calculate(plan, start, end).hasCharges());
    }

    @Test
    void doesNotChargeUnlimitedPlans() {
        when(planLimitRepository.findByPlanIdAndMetricAndDeletedFalse(any(), eq(UsageMetric.API_CALLS)))
                .thenReturn(Optional.of(PlanLimit.builder().unlimited(true).build()));

        assertFalse(service.calculate(plan, start, end).hasCharges());
    }
}
