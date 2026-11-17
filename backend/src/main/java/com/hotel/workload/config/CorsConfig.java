package com.hotel.workload.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-origin access for a browser frontend served from another site (the Vercel deployment), and only for the origins listed in
 * {@code taskotel.cors.allowed-origins} ({@code CORS_ALLOWED_ORIGINS}).
 *
 * <ul>
 *   <li><b>Off by default.</b> With the property empty this class is not even registered, so local development (which uses the Vite proxy) and
 *       every existing test behave exactly as before.</li>
 *   <li><b>Exact origins only.</b> A wildcard, a path or anything that is not an http(s) origin stops the application from starting, so a
 *       mistake can never open the API to every site.</li>
 *   <li><b>No cookies.</b> Sign-in is a bearer token in the Authorization header, so credentials mode stays off.</li>
 *   <li><b>Authorization is untouched.</b> This is a servlet filter ahead of Spring Security that answers browser preflight requests and adds
 *       the response headers. It grants no access: a request without a valid token is refused by the same URL rules as before.</li>
 *   <li>Only the {@code /api/**} paths are covered.</li>
 * </ul>
 */
@Configuration
@ConditionalOnExpression("'${taskotel.cors.allowed-origins:}'.trim().length() > 0")
public class CorsConfig {

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    static final List<String> HEADERS = List.of("Authorization", "Content-Type", "Accept");
    static final long MAX_AGE_SECONDS = 3600;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(@Value("${taskotel.cors.allowed-origins}") String configured) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseOrigins(configured));
        configuration.setAllowedMethods(METHODS);
        configuration.setAllowedHeaders(HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(MAX_AGE_SECONDS);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // Before Spring Security's filter chain, so a preflight request (which carries no token) is answered before authorization
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** The configured origins, trimmed, without a trailing slash; refuses anything that is not an exact http(s) origin. */
    static List<String> parseOrigins(String configured) {
        List<String> origins = new ArrayList<>();
        for (String raw : configured == null ? new String[0] : configured.split(",")) {
            String origin = raw.trim();
            if (origin.isEmpty()) continue;
            while (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
            if (!origin.matches("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?")) {
                throw new IllegalStateException("CORS_ALLOWED_ORIGINS must list exact origins such as https://app.example.com (scheme, host and optional port; "
                        + "no wildcard, path or query). Invalid entry: " + raw.trim());
            }
            origins.add(origin);
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS is set but contains no origin");
        }
        return origins;
    }
}
