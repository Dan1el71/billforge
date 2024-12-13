package com.billforge.app.demo;

import com.billforge.auth.entity.Role;
import com.billforge.auth.entity.User;
import com.billforge.auth.repository.RoleRepository;
import com.billforge.auth.repository.UserRepository;
import com.billforge.billing.entity.Subscription;
import com.billforge.billing.repository.SubscriptionRepository;
import com.billforge.billing.service.InvoiceService;
import com.billforge.shared.enums.auth.Status;
import com.billforge.shared.enums.billing.BillingCycle;
import com.billforge.shared.enums.billing.SubscriptionStatus;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.tenant.entity.Plan;
import com.billforge.tenant.entity.Tenant;
import com.billforge.tenant.repository.PlanRepository;
import com.billforge.tenant.service.TenantFlywayMigrationService;
import com.billforge.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Creates a demo tenant with its schema, an owner, an active subscription and one
 * invoice. Active only with the demo profile, and idempotent: it does nothing when
 * the demo tenant already exists.
 */
@Component
@Profile("demo")
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {

    private static final String DEMO_SLUG = "demo";
    private static final String DEMO_SCHEMA = "tenant_demo";
    private static final String DEMO_EMAIL = "owner@demo.billforge.local";
    private static final String DEMO_PASSWORD = "Demo12345!";

    private final TenantService tenantService;
    private final TenantFlywayMigrationService migrationService;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceService invoiceService;

    @Override
    public void run(ApplicationArguments args) {
        if (tenantService.tenantSlugExists(DEMO_SLUG)) {
            log.info("Demo tenant already present, skipping seed");
            return;
        }

        Plan plan = planRepository.findByCodeIgnoreCaseAndDeletedFalse("PRO")
                .orElseThrow(() -> new IllegalStateException("PRO plan is not seeded"));

        Tenant tenant = tenantService.saveTenant(Tenant.builder()
                .name("Demo Co")
                .slug(DEMO_SLUG)
                .schemaName(DEMO_SCHEMA)
                .ownerEmail(DEMO_EMAIL)
                .plan(plan)
                .status(Status.ACTIVE)
                .onboardingStep(3)
                .build());

        migrationService.createSchemaAndMigrate(DEMO_SCHEMA);

        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(DEMO_SCHEMA);
        try {
            Role owner = roleRepository.findByName("OWNER")
                    .orElseThrow(() -> new IllegalStateException("OWNER role is not seeded"));
            userRepository.save(User.builder()
                    .name("Demo Owner")
                    .email(DEMO_EMAIL)
                    .password(passwordEncoder.encode(DEMO_PASSWORD))
                    .role(owner)
                    .status(Status.ACTIVE)
                    .build());

            Instant start = Instant.now().minus(15, ChronoUnit.DAYS);
            Instant end = start.plus(30, ChronoUnit.DAYS);

            Subscription subscription = subscriptionRepository.save(Subscription.builder()
                    .planId(plan.getId())
                    .status(SubscriptionStatus.ACTIVE)
                    .billingCycle(BillingCycle.MONTHLY)
                    .currentPeriodStart(start)
                    .currentPeriodEnd(end)
                    .build());

            invoiceService.generateInvoice(subscription.getId(), start, end);

            log.info("Demo tenant ready: {} / {} (tenantId={}, schema={})",
                    DEMO_EMAIL, DEMO_PASSWORD, tenant.getId(), DEMO_SCHEMA);
        } finally {
            TenantContext.clear();
        }
    }
}
