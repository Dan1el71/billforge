package com.billforge.support;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Gives the tests a default tenant schema, so repositories that run without an
 * explicit tenant context have the tenant tables available.
 */
@Configuration
public class DefaultTenantSchemaConfig {

    @Bean
    public Flyway defaultTenantFlyway(DataSource dataSource,
                                      @Value("${app.tenancy.default-schema:public}") String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/tenant")
                .schemas(schema)
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
