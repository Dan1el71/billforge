package com.billforge.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.mail")
@Getter
@Setter
public class NotificationProperties {
    private String fromAddress = "noreply@billforge.com";
    private String fromName = "BILLFORGE Platform";
}