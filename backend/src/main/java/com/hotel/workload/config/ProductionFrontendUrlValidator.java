package com.hotel.workload.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production never starts with a wrong web address for email links. FRONTEND_BASE_URL absent means the production default
 * (application-prod.yml); present but empty, malformed, http, or pointing at the local machine stops the application with a clear message, so an
 * invitation email can never carry a localhost or broken link. Local development is not affected (this bean exists only in production).
 */
@Component
@Profile("prod | production")
public class ProductionFrontendUrlValidator implements InitializingBean {

    private final String frontendBaseUrl;

    public ProductionFrontendUrlValidator(@Value("${taskotel.frontend-base-url:}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void afterPropertiesSet() {
        String problem = FrontendBaseUrl.productionProblem(frontendBaseUrl);
        if (problem != null) {
            throw new IllegalStateException("Production startup failed: FRONTEND_BASE_URL is invalid: " + problem
                    + ". Set it to the web app's https address (for example https://app.example.com), or remove the variable to use the production default.");
        }
    }
}
