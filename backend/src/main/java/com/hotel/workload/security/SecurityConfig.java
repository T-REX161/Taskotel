package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * URL-level authorization. The policy is <b>deny by default</b>:
 *
 * <ul>
 *   <li>Public endpoints are listed one by one (method and path): sign-in and the onboarding and password-reset flows.</li>
 *   <li>Every other API endpoint is listed one by one with the roles allowed to call it.</li>
 *   <li>HEAD is allowed only where the same path allows GET, for exactly the same roles (see {@code allowGet}).</li>
 *   <li>Anything else under {@code /api/**} (an unknown route, or a known route with a method that is not listed, OPTIONS and a
 *       HEAD without a GET rule included) is refused: 401 when nobody is signed in, 403 when someone is. There is no "any signed-in user" fallback, so a
 *       new endpoint is closed until a rule for it is added here.</li>
 *   <li>Paths outside {@code /api} carry no data (the single-page app files and the error page) and stay open.</li>
 * </ul>
 *
 * This is the first layer only. The services decide again from the database (see {@link CurrentUserScopeResolver}), so a route that
 * is added here by mistake still cannot return another department's data, and every Admin-only service operation checks for itself.
 * {@code SecurityConfigEndpointCoverageTest} fails when a controller route has no rule here.
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "MANAGER";
    private static final String STAFF = "STAFF";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;

    @Autowired
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, Environment environment) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
    }

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this(jwtAuthenticationFilter, null);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isProductionEnvironment() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        boolean isProd = isProductionEnvironment();

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
            )
            .authorizeHttpRequests(authz -> configureRules(authz, isProd))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * A GET rule, with HEAD allowed for exactly the same roles on exactly the same paths: HEAD is answered by the GET handler, so it must
     * never be more or less open than GET. This is the only place HEAD is authorized; there is no general HEAD rule, and a path with no GET
     * rule has no HEAD rule either.
     */
    private static void allowGet(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authz, String[] paths, String... roles) {
        authz.requestMatchers(HttpMethod.GET, paths).hasAnyRole(roles);
        authz.requestMatchers(HttpMethod.HEAD, paths).hasAnyRole(roles);
    }

    private static void configureRules(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authz, boolean isProd) {

        // ── Public (no sign-in): the sign-in, onboarding and password-reset flows ────────────────────────────────────────────
        authz.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/email-verification/send", "/api/auth/email-verification/verify",
                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm").permitAll();
        authz.requestMatchers(HttpMethod.PUT, "/api/auth/credentials/setup").permitAll();

        // ── Any signed-in user, own data only (the account comes from the token, never from the request) ────────────────────
        allowGet(authz, new String[]{"/api/profile", "/api/notification-preferences"}, ADMIN, MANAGER, STAFF);
        authz.requestMatchers(HttpMethod.PUT, "/api/profile/display-name", "/api/profile/phone", "/api/profile/preferences",
                "/api/profile/password", "/api/notification-preferences").hasAnyRole(ADMIN, MANAGER, STAFF);
        authz.requestMatchers(HttpMethod.POST, "/api/profile/picture", "/api/profile/email-change/request",
                "/api/profile/email-change/confirm").hasAnyRole(ADMIN, MANAGER, STAFF);
        authz.requestMatchers(HttpMethod.DELETE, "/api/profile/picture").hasAnyRole(ADMIN, MANAGER, STAFF);

        // ── Users and Manager departments: Admin only ─────────────────────────────────────────────────────────────────────────
        allowGet(authz, new String[]{"/api/users", "/api/users/*/manager-department", "/api/users/*/manager-department/history"}, ADMIN);
        authz.requestMatchers(HttpMethod.PUT, "/api/users/*/manager-department").hasRole(ADMIN);
        authz.requestMatchers(HttpMethod.PATCH, "/api/users/*/deactivate", "/api/users/*/activate").hasRole(ADMIN);
        authz.requestMatchers(HttpMethod.DELETE, "/api/users/*").hasRole(ADMIN);

        // ── Staff: Admin and Manager (a Manager only within their own department, decided by the service); removal is Admin only ─
        allowGet(authz, new String[]{"/api/staff", "/api/staff/username-availability"}, ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.POST, "/api/staff").hasAnyRole(ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.PUT, "/api/staff/*").hasAnyRole(ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.PATCH, "/api/staff/*/activate", "/api/staff/*/deactivate").hasAnyRole(ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.DELETE, "/api/staff/*").hasRole(ADMIN);

        // ── Tasks and assignments: everyone may read (the service limits each role to its own scope); Admin and Manager write ──
        allowGet(authz, new String[]{"/api/tasks", "/api/assignments"}, ADMIN, MANAGER, STAFF);
        authz.requestMatchers(HttpMethod.POST, "/api/tasks", "/api/assignments").hasAnyRole(ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.PUT, "/api/tasks/*", "/api/assignments/*").hasAnyRole(ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.DELETE, "/api/tasks/*").hasAnyRole(ADMIN, MANAGER);

        // ── Workload and dashboard ────────────────────────────────────────────────────────────────────────────────────────────
        allowGet(authz, new String[]{"/api/workload", "/api/dashboard/summary"}, ADMIN, MANAGER, STAFF);
        allowGet(authz, new String[]{"/api/workload/recommendations"}, ADMIN, MANAGER);

        // ── Departments: everyone may read (how much each role sees is decided by the service); managing them is Admin only ───
        allowGet(authz, new String[]{"/api/departments"}, ADMIN, MANAGER, STAFF);
        authz.requestMatchers(HttpMethod.POST, "/api/departments").hasRole(ADMIN);
        authz.requestMatchers(HttpMethod.PUT, "/api/departments/*").hasRole(ADMIN);
        authz.requestMatchers(HttpMethod.DELETE, "/api/departments/*").hasRole(ADMIN);

        // ── Cross-department support: confirming a plan is Admin only (the service checks too); the rest is Admin and Manager ──
        authz.requestMatchers(HttpMethod.POST, "/api/support/assignments").hasRole(ADMIN);
        allowGet(authz, new String[]{"/api/support/options", "/api/support/assignments"}, ADMIN, MANAGER);
        authz.requestMatchers(HttpMethod.POST, "/api/support/preview", "/api/support/assignments/*/archive",
                "/api/support/assignments/*/end").hasAnyRole(ADMIN, MANAGER);

        // ── Development-only authorization probes: not even mapped in production, and denied here as well ─────────────────────
        if (!isProd) {
            allowGet(authz, new String[]{"/api/test/admin"}, ADMIN);
            allowGet(authz, new String[]{"/api/test/manager"}, ADMIN, MANAGER);
            allowGet(authz, new String[]{"/api/test/staff"}, ADMIN, MANAGER, STAFF);
        }

        // ── Deny by default: every other API route and method. 401 without a sign-in, 403 with one. ───────────────────────────
        authz.requestMatchers("/api", "/api/**").denyAll();

        // Outside /api only the single-page app files and the error page are served; they hold no data
        authz.anyRequest().permitAll();
    }
}
