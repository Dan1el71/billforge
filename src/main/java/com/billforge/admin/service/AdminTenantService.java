package com.billforge.admin.service;

import com.billforge.admin.dto.AdminTenantDetailResponse;
import com.billforge.admin.dto.AdminTenantListResponse;
import com.billforge.admin.dto.ChangeTenantPlanRequest;
import com.billforge.admin.dto.ChangeTenantStatusRequest;
import com.billforge.auth.service.PermissionCacheService;
import com.billforge.tenant.dto.tenant.TenantResponse;
import com.billforge.auth.dto.user.UserResponse;
import com.billforge.tenant.entity.Tenant;
import com.billforge.tenant.entity.Plan;
import com.billforge.tenant.mapper.TenantMapper;
import com.billforge.shared.enums.auth.Status;
import com.billforge.shared.event.audit.AuditLogEvent;
import com.billforge.shared.enums.audit.AuditAction;
import com.billforge.shared.enums.audit.AuditEntityType;
import com.billforge.shared.exception.TenantException;
import com.billforge.shared.multitenancy.TenantContext;
import com.billforge.tenant.service.TenantService;
import com.billforge.billing.event.outbox.OutboxEventPublisher;
import com.billforge.shared.util.SecurityUtils;
import com.billforge.tenant.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for system administrators to manage all tenants globally.
 * Operates on the public schema but uses JdbcTemplate/TenantContext proxy
 * to fetch granular data from specific tenant schemas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTenantService {

    private final TenantService tenantService;
    private final AdminTenantScopedService adminTenantScopedService;
    private final JdbcTemplate jdbcTemplate;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TenantMapper tenantMapper;
    private final PermissionCacheService permissionCacheService;
    private final PlanService planService;

    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<AdminTenantListResponse> getAllTenants(Status status, Long planId, Pageable pageable) {
        log.info("Admin fetching all tenants. Filters -> status: {}, planId: {}", status, planId);

        Page<Tenant> tenants;
        if (status != null && planId != null) {
            List<Tenant> filtered = tenantService.getTenantsByPlanIdAndStatus(planId, status);
            tenants = new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
        } else if (status != null) {
            tenants = tenantService.getTenantsByStatus(status, pageable);
        } else if (planId != null) {
            List<Tenant> byPlan = tenantService.getTenantsByPlanId(planId);
            tenants = new org.springframework.data.domain.PageImpl<>(byPlan, pageable, byPlan.size());
        } else {
            tenants = tenantService.getAllTenantsPaged(pageable);
        }

        return tenants.map(this::mapToListResponse);
    }

    @Transactional(readOnly = true)
    public AdminTenantDetailResponse getTenantDetail(Long tenantId) {
        log.info("Admin fetching tenant details: {}", tenantId);
        Tenant tenant = tenantService.getTenantById(tenantId);

        String schema = "\"" + tenant.getSchemaName() + "\"";

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

        return AdminTenantDetailResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .schemaName(tenant.getSchemaName())
                .planId(tenant.getPlan() != null ? tenant.getPlan().getId() : null)
                .planCode(tenant.getPlan() != null ? tenant.getPlan().getCode() : null)
                .planName(tenant.getPlan() != null ? tenant.getPlan().getName() : null)
                .status(tenant.getStatus())
                .userCount(userCount != null ? userCount : 0L)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }

    @Transactional
    public TenantResponse changeTenantStatus(Long tenantId, ChangeTenantStatusRequest request) {
        log.info("Admin changing tenant status: {} to {}", tenantId, request.getStatus());

        tenantService.updateTenantStatus(tenantId, request.getStatus());
        Tenant tenant = tenantService.getTenantById(tenantId);

        permissionCacheService.evictTenant(tenant.getSchemaName());

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .changesAfter(Map.of("status", request.getStatus().name()))
                .description("Admin changed tenant status to: " + request.getStatus())
                .module("ADMIN_TENANT_MANAGEMENT")
                .build());

        return tenantMapper.toResponse(tenant);
    }

    @Transactional
    public TenantResponse changeTenantPlan(Long tenantId, ChangeTenantPlanRequest request) {
        log.info("Admin changing tenant plan: {} to planId={}", tenantId, request.getPlanId());
        Tenant tenant = tenantService.getTenantById(tenantId);

        Plan plan = planService.getPlanById(request.getPlanId());
        tenant.setPlan(plan);
        tenant = tenantService.saveTenant(tenant);

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.UPDATE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .changesAfter(Map.of("planId", request.getPlanId(), "planCode", plan.getCode()))
                .description("Admin changed tenant plan to: " + plan.getCode())
                .module("ADMIN_TENANT_MANAGEMENT")
                .build());

        return tenantMapper.toResponse(tenant);
    }

    public Page<UserResponse> getTenantUsers(Long tenantId, Pageable pageable) {
        log.info("Admin fetching users for tenant: {}", tenantId);
        Tenant tenant = tenantService.getTenantById(tenantId);

        TenantContext.setTenantId(tenant.getId());
        try {
            log.debug("AdminTenantService.getTenantUsers: switching to schema={}", TenantContext.getTenantId());
            return adminTenantScopedService.getUsersInTenant(pageable);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public void deleteTenant(Long tenantId) {
        log.info("Admin soft deleting tenant: {}", tenantId);
        Tenant tenant = tenantService.getTenantById(tenantId);

        tenant.setDeleted(true);
        tenant.setDeletedAt(Instant.now());
        tenantService.saveTenant(tenant);

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.DELETE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .description("Admin deleted tenant: " + tenant.getName())
                .module("ADMIN_TENANT_MANAGEMENT")
                .severity("WARN")
                .build());
    }

    private AdminTenantListResponse mapToListResponse(Tenant tenant) {
        String schema = "\"" + tenant.getSchemaName() + "\"";
        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

        return AdminTenantListResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .schemaName(tenant.getSchemaName())
                .planId(tenant.getPlan() != null ? tenant.getPlan().getId() : null)
                .planCode(tenant.getPlan() != null ? tenant.getPlan().getCode() : null)
                .planName(tenant.getPlan() != null ? tenant.getPlan().getName() : null)
                .status(tenant.getStatus())
                .userCount(userCount != null ? userCount : 0L)
                .createdAt(tenant.getCreatedAt())
                .build();
    }
}