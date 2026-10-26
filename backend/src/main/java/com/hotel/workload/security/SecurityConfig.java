package com.hotel.workload.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

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
            .authorizeHttpRequests(authz -> {
                authz
                    .requestMatchers("/api/auth/login", "/api/auth/email-verification/**", "/api/auth/credentials/setup", "/api/auth/password-reset/**").permitAll()
                    .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/staff", "/api/staff/**").hasRole("ADMIN")
                    .requestMatchers("/api/staff", "/api/staff/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/tasks").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/tasks", "/api/tasks/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/tasks", "/api/tasks/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/assignments", "/api/assignments/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/assignments", "/api/assignments/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/assignments", "/api/assignments/**").hasAnyRole("ADMIN", "MANAGER", "STAFF");

                if (isProd) {
                    authz.requestMatchers("/api/test", "/api/test/**").denyAll();
                } else {
                    authz.requestMatchers("/api/test/admin").hasRole("ADMIN")
                         .requestMatchers("/api/test/manager").hasAnyRole("ADMIN", "MANAGER")
                         .requestMatchers("/api/test/staff").hasAnyRole("ADMIN", "MANAGER", "STAFF");
                }

                authz
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/workload").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/dashboard", "/api/dashboard/**").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/departments", "/api/departments/**").hasAnyRole("ADMIN", "MANAGER", "STAFF")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/departments").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/departments/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/departments/**").hasAnyRole("ADMIN", "MANAGER")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
