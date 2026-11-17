package com.hotel.workload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What a Render deployment depends on: the liveness endpoint, the port, the production datasource and pool, and secret-free Docker files. */
@SpringBootTest
public class HealthAndDeploymentConfigTest {

    @Autowired private WebApplicationContext context;

    private static PropertySource<?> yaml(String name) throws Exception {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).get(0);
    }

    @Test
    @DisplayName("GET /healthz is public, needs no database or token, and answers {status: UP}; CORS is off unless configured")
    public void healthEndpoint() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertFalse(context.containsBean("corsFilterRegistration"), "no CORS support unless CORS_ALLOWED_ORIGINS is set");
        // everything under /api is still closed to an anonymous caller
        mockMvc.perform(get("/api/staff")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The server port follows Render's PORT (8080 locally); CORS origins come only from CORS_ALLOWED_ORIGINS, empty by default")
    public void portAndCorsComeFromTheEnvironment() throws Exception {
        // the main file on disk: the test resources have their own application.yml, which shadows it on the classpath
        PropertySource<?> main = new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yml")).get(0);
        assertEquals("${PORT:8080}", main.getProperty("server.port"));
        assertEquals("${CORS_ALLOWED_ORIGINS:}", main.getProperty("taskotel.cors.allowed-origins"));
    }

    @Test
    @DisplayName("Production datasource: URL, user and password from the environment (TLS required by default), a small pool, graceful shutdown, no error details")
    public void productionDatasource() throws Exception {
        for (String file : List.of("application-prod.yml", "application-production.yml")) {
            PropertySource<?> p = yaml(file);
            String url = String.valueOf(p.getProperty("spring.datasource.url"));
            assertTrue(url.startsWith("${DB_URL:jdbc:postgresql://${DB_HOST}"), file + " url: " + url);
            assertTrue(url.contains("sslmode=${DB_SSLMODE:require}"), file + " requires TLS by default");
            assertEquals("${DB_USER}", p.getProperty("spring.datasource.username"));
            assertEquals("${DB_PASSWORD}", p.getProperty("spring.datasource.password"));
            assertEquals("${DB_POOL_MAX_SIZE:5}", p.getProperty("spring.datasource.hikari.maximum-pool-size"));
            assertEquals("validate", p.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("graceful", p.getProperty("server.shutdown"));
            assertEquals("never", p.getProperty("server.error.include-stacktrace"));
            assertEquals("never", p.getProperty("server.error.include-message"));
            assertEquals("never", p.getProperty("server.error.include-binding-errors"));
        }
        // The two profile files stay identical (the "production" alias loads both)
        PropertySource<?> a = yaml("application-prod.yml");
        PropertySource<?> b = yaml("application-production.yml");
        for (String key : List.of("spring.datasource.url", "spring.datasource.hikari.maximum-pool-size", "server.shutdown", "jwt.secret", "sendgrid.api-key")) {
            assertEquals(a.getProperty(key), b.getProperty(key), key);
        }
    }

    @Test
    @DisplayName("The production JDBC URL resolves: built from DB_HOST/DB_PORT/DB_NAME with sslmode=require, DB_SSLMODE overrides it, a full DB_URL wins")
    public void productionUrlResolves() throws Exception {
        String template = String.valueOf(yaml("application-prod.yml").getProperty("spring.datasource.url"));
        java.util.function.Function<java.util.Map<String, Object>, String> resolve = vars -> {
            org.springframework.core.env.StandardEnvironment env = new org.springframework.core.env.StandardEnvironment();
            env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("vars", vars));
            return env.resolveRequiredPlaceholders(template);
        };
        assertEquals("jdbc:postgresql://ep-x.neon.tech:5432/taskotel?sslmode=require",
                resolve.apply(java.util.Map.of("DB_HOST", "ep-x.neon.tech", "DB_NAME", "taskotel")));
        assertEquals("jdbc:postgresql://db.internal:6543/workload_balancer?sslmode=disable",
                resolve.apply(java.util.Map.of("DB_HOST", "db.internal", "DB_PORT", "6543", "DB_SSLMODE", "disable")));
        assertEquals("jdbc:postgresql://given.example/db?sslmode=verify-full",
                resolve.apply(java.util.Map.of("DB_URL", "jdbc:postgresql://given.example/db?sslmode=verify-full")));
        assertThrows(IllegalArgumentException.class, () -> resolve.apply(java.util.Map.of()), "no DB_HOST and no DB_URL: startup must fail, not guess");
    }

    @Test
    @DisplayName("No configuration file holds a literal secret: every credential is a ${PLACEHOLDER}")
    public void noLiteralSecrets() throws Exception {
        Pattern literal = Pattern.compile("(?im)^\\s*(password|secret|api-key|api-secret|jwt-secret)\\s*:\\s*([^\\s$#][^\\s#]*)\\s*$");
        for (String file : List.of("application.yml", "application-prod.yml", "application-production.yml")) {
            String text = Files.readString(Path.of("src/main/resources", file));
            Matcher m = literal.matcher(text);
            if (m.find()) fail(file + " has a literal value for " + m.group(1));
        }
    }

    @Test
    @DisplayName("Dockerfile: Java 25 multi-stage build with the Maven wrapper, non-root, PORT-driven, and no secret baked in; .dockerignore keeps secrets and build output out")
    public void dockerFiles() throws Exception {
        String docker = Files.readString(Path.of("Dockerfile"));
        assertTrue(docker.contains("FROM eclipse-temurin:25-jdk AS build") && docker.contains("FROM eclipse-temurin:25-jre AS runtime"));
        assertTrue(docker.contains("./mvnw") && docker.contains("USER taskotel") && docker.contains("SPRING_PROFILES_ACTIVE=prod"));
        assertTrue(docker.contains("sed -i 's/\\r$//' mvnw"), "CRLF-safe wrapper");
        Pattern credential = Pattern.compile("(?i)(secret|password|api_?key|token|DB_|JWT|SENDGRID|CLOUDINARY)");
        for (String line : docker.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("ENV ") || t.startsWith("ARG ")) {
                assertFalse(credential.matcher(t).find(), "no credential in an image layer: " + t);
            }
        }
        List<String> ignored = Files.readAllLines(Path.of(".dockerignore"));
        for (String must : List.of(".env", ".env.*", "target/", ".git/", ".idea/", ".vscode/", "*.log")) assertTrue(ignored.contains(must), must);
    }
}
