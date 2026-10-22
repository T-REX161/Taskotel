package com.hotel.workload.config;

import com.hotel.workload.verification.SendGridProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | production")
public class ProductionSecurityValidator implements InitializingBean {

    private final SendGridProperties sendGridProperties;

    public ProductionSecurityValidator(SendGridProperties sendGridProperties) {
        this.sendGridProperties = sendGridProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (sendGridProperties == null
                || sendGridProperties.getApiKey() == null
                || sendGridProperties.getApiKey().trim().isEmpty()
                || sendGridProperties.getApiKey().trim().equalsIgnoreCase("test-sendgrid-api-key")
                || sendGridProperties.getApiKey().trim().startsWith("test-")) {
            throw new IllegalStateException(
                    "Production startup failed: Required environment variable SENDGRID_API_KEY is missing or contains an invalid/placeholder test key. Please configure a valid SendGrid API key."
            );
        }

        if (sendGridProperties.getFromEmail() == null
                || sendGridProperties.getFromEmail().trim().isEmpty()
                || !sendGridProperties.getFromEmail().contains("@")) {
            throw new IllegalStateException(
                    "Production startup failed: Required environment variable SENDGRID_FROM_EMAIL is missing or invalid. Please configure a valid sender email address."
            );
        }
    }
}
