package com.hotel.workload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /actuator/health for the hosting platform. The test resources have their own application.yml that shadows the main one, so the
 * {@code management.*} settings are read from the real src/main/resources/application.yml and applied here: what is tested is what ships.
 */
@SpringBootTest
@ContextConfiguration(initializers = ActuatorHealthTest.MainManagementSettings.class)
public class ActuatorHealthTest {

    public static class MainManagementSettings implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                PropertySource<?> main = new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yml")).get(0);
                Map<String, Object> management = new HashMap<>();
                for (String name : ((EnumerablePropertySource<?>) main).getPropertyNames()) {
                    if (name.startsWith("management.")) management.put(name, main.getProperty(name));
                }
                assertFalse(management.isEmpty(), "application.yml must configure management.*");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("mainManagement", management));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("GET /actuator/health is public and answers exactly {\"status\":\"UP\"}: no details, no components, no token")
    public void healthIsUpAndMinimal() throws Exception {
        mvc().perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("No other actuator endpoint is reachable: env, beans, metrics, info, the index and health sub-paths are all refused; only GET reads health")
    public void nothingElseIsExposed() throws Exception {
        MockMvc mvc = mvc();
        for (String path : new String[]{"/actuator", "/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/info", "/actuator/heapdump",
                "/actuator/configprops", "/actuator/mappings", "/actuator/loggers", "/actuator/health/db", "/actuator/health/liveness"}) {
            int s = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertTrue(s == 401 || s == 403 || s == 404, path + " must not be served, was " + s);
        }
        assertTrue(mvc.perform(post("/actuator/health")).andReturn().getResponse().getStatus() >= 400, "POST is not a health check");
    }

    @Test
    @DisplayName("The shipped configuration exposes only health, never shows details, and runs on the application port (PORT)")
    public void configurationIsMinimal() throws Exception {
        PropertySource<?> main = new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yml")).get(0);
        assertEquals("health", main.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", main.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", main.getProperty("management.endpoint.health.show-components"));
        assertNull(main.getProperty("management.server.port"), "the probe shares the application port, so it follows PORT");
        assertEquals("${PORT:8080}", main.getProperty("server.port"));
        for (String file : new String[]{"application-prod.yml", "application-production.yml"}) {
            PropertySource<?> p = new YamlPropertySourceLoader().load(file, new FileSystemResource("src/main/resources/" + file)).get(0);
            assertNull(p.getProperty("management.endpoints.web.exposure.include"), file + " must not widen exposure");
        }
    }

    @Test
    @DisplayName("The existing /healthz probe and the closed /api rules are unchanged")
    public void existingBehaviourUnchanged() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/staff")).andExpect(status().isUnauthorized());
    }
}
