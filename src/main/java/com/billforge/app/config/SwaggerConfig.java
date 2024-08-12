package com.billforge.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

        @Bean
        public OpenAPI openAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Billforge API")
                                                .version("1.0.0")
                                                .description("Multi-tenant billing platform: schema-per-tenant isolation, plans, "
                                                                +
                                                                "subscriptions, proration, invoices, usage metering and Stripe payments.")
                                                .contact(new Contact()
                                                                .name("Billforge Support")
                                                                .email("support@billforge.local"))
                                                .license(new License()
                                                                .name("MIT License")
                                                                .url("https://opensource.org/licenses/MIT")))
                                .addSecurityItem(new SecurityRequirement().addList("Bearer JWT"))
                                .components(new Components()
                                                .addSecuritySchemes("Bearer JWT",
                                                                new SecurityScheme()
                                                                                .type(SecurityScheme.Type.HTTP)
                                                                                .scheme("bearer")
                                                                                .bearerFormat("JWT")
                                                                                .description("Enter JWT token")));
        }
}
