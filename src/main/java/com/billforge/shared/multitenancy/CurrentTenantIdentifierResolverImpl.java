package com.billforge.shared.multitenancy;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CurrentTenantIdentifierResolverImpl
        implements CurrentTenantIdentifierResolver<String> {

    @Value("${app.tenancy.default-schema:public}")
    private String defaultSchema = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String schema = TenantContext.getSchemaName();
        if (schema == null || schema.isBlank()) {
            log.debug(">>> resolveCurrentTenantIdentifier: no schema in context, defaulting to '{}'", defaultSchema);
            return defaultSchema;
        }
        log.debug(">>> resolveCurrentTenantIdentifier returning: '{}'", schema);
        return schema;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

