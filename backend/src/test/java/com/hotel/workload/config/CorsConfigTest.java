package com.hotel.workload.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production CORS for the separate frontend (Vercel): only the configured origins, no wildcard, no credentials, and no change to who may call
 * what. The filter is exercised in front of the real Spring Security chain.
 */
@SpringBootTest
@TestPropertySource(properties = "taskotel.cors.allowed-origins= https://taskotel.vercel.app , https://staging.example.com/ ")
public class CorsConfigTest {

    private static final String ALLOWED = "https://taskotel.vercel.app";

    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        @SuppressWarnings("unchecked")
        FilterRegistrationBean<jakarta.servlet.Filter> cors = context.getBean("corsFilterRegistration", FilterRegistrationBean.class);
        // The CORS filter first (as in the running server, where it is registered ahead of Spring Security), then the security chain
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(cors.getFilter()).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("A browser preflight from an allowed origin is answered without a token, with that origin only, no credentials and a cache time")
    public void preflightFromAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login").header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @Test
    @DisplayName("A trailing slash and surrounding spaces in the configured list are tolerated (the second origin works too)")
    public void configuredListIsNormalised() throws Exception {
        mockMvc.perform(options("/api/auth/login").header("Origin", "https://staging.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://staging.example.com"));
    }

    @Test
    @DisplayName("A preflight, and a normal request, from any other origin are refused")
    public void otherOriginsAreRefused() throws Exception {
        mockMvc.perform(options("/api/auth/login").header("Origin", "https://evil.example.com").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(get("/api/profile").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("CORS grants no access: a protected endpoint without a token is still 401 for an allowed origin, and an unknown API route is still denied")
    public void authorizationIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/profile").header("Origin", ALLOWED)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
        mockMvc.perform(get("/api/users").header("Origin", ALLOWED)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/unknown").header("Origin", ALLOWED)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Requests without an Origin header (same-origin, curl, server to server) are not affected")
    public void noOriginIsUnaffected() throws Exception {
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Only /api is covered: the health endpoint gets no CORS headers")
    public void onlyApiPathsAreCovered() throws Exception {
        mockMvc.perform(get("/healthz").header("Origin", ALLOWED)).andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Origins are parsed strictly: exact http(s) origins only; a wildcard, path, wrong scheme or empty list stops startup")
    public void parsing() {
        assertEquals(List.of("https://a.example.com", "http://localhost:3000"), CorsConfig.parseOrigins(" https://a.example.com/ , http://localhost:3000 ,, "));
        for (String bad : new String[]{"*", "https://*.vercel.app", "https://a.example.com/app", "ftp://a.example.com", "a.example.com", "https://a.example.com?x=1", "null"}) {
            assertThrows(IllegalStateException.class, () -> CorsConfig.parseOrigins(bad), bad);
        }
        assertThrows(IllegalStateException.class, () -> CorsConfig.parseOrigins(" , "));
        assertThrows(IllegalStateException.class, () -> CorsConfig.parseOrigins(null));
        assertFalse(CorsConfig.METHODS.contains("TRACE"));
        assertEquals(List.of("Authorization", "Content-Type", "Accept"), CorsConfig.HEADERS);
    }
}
