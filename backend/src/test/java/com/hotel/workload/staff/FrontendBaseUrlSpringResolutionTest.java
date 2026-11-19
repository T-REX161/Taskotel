package com.hotel.workload.staff;

import com.hotel.workload.config.ProductionFrontendUrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How Spring Boot itself resolves FRONTEND_BASE_URL, with the REAL machinery: a real SpringApplication starts, its real config-data loader reads
 * the real src/main/resources/application.yml and the real production profile file, and the variable comes from Spring's own environment
 * property source (SystemEnvironmentPropertySource, the class that serves real environment variables), so a variable that is PRESENT BUT
 * EMPTY is a genuine empty-valued entry, which cannot be set on a Windows process. Nothing here is a hand-built string or a mocked resolver.
 */
public class FrontendBaseUrlSpringResolutionTest {

    private static final String PRODUCTION = "https://taskotel.vercel.app";
    private static final String LOCAL = "http://localhost:3000";

    /** Only the property is read: no web server, no database, no auto-configuration. */
    @Configuration
    static class ReadOnlyConfig {}

    /** The same, plus the production startup check that is component-scanned in the real application. */
    @Configuration
    @Import(ProductionFrontendUrlValidator.class)
    static class WithStartupCheck {}

    private static ConfigurableApplicationContext start(Class<?> config, String profile, Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        // the real class that serves environment variables, with exactly the variables this case defines (nothing from the machine)
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(environmentVariables)));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(config).web(WebApplicationType.NONE).environment(environment).bannerMode(org.springframework.boot.Banner.Mode.OFF);
        if (profile != null) builder.profiles(profile.split(","));
        return builder.run(
                // the real files of the application, not the test copy, and never a developer's own .env
                "--spring.config.location=optional:file:src/main/resources/",
                "--spring.config.import=optional:file:src/test/resources/no-such-file.properties");
    }

    /** What Spring resolves taskotel.frontend-base-url to (no startup check involved). */
    private static String resolved(String profile, Map<String, Object> variables) {
        try (ConfigurableApplicationContext context = start(ReadOnlyConfig.class, profile, variables)) {
            return context.getEnvironment().getProperty("taskotel.frontend-base-url");
        }
    }

    /** null when the production application would start; otherwise the startup failure message. */
    private static String startupFailure(String profile, Map<String, Object> variables) {
        try (ConfigurableApplicationContext context = start(WithStartupCheck.class, profile, variables)) {
            assertNotNull(context);
            return null;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return root.getMessage();
        }
    }

    private static Map<String, Object> env(String value) {
        return value == null ? Map.of() : Map.of("FRONTEND_BASE_URL", value);
    }

    // ── what Spring resolves ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring, production profiles: variable ABSENT resolves to the production default")
    public void absentResolvesToTheProductionDefault() {
        for (String profile : List.of("prod", "production")) assertEquals(PRODUCTION, resolved(profile, env(null)), profile);
    }

    @Test
    @DisplayName("Spring, production profiles: variable PRESENT BUT EMPTY resolves to the empty string, not to the default and not to localhost")
    public void emptyVariableResolvesToEmptyNotToLocalhost() {
        for (String profile : List.of("prod", "production")) {
            String value = resolved(profile, env(""));
            assertEquals("", value, profile + ": a present empty variable is a value; the placeholder default applies only to an ABSENT variable");
            assertFalse(value.contains("localhost"), profile);
        }
    }

    @Test
    @DisplayName("Spring, production profiles: a whitespace-only variable resolves to that whitespace, untouched")
    public void whitespaceVariableResolvesToWhitespace() {
        for (String profile : List.of("prod", "production")) {
            for (String blank : List.of(" ", "   ", "\t", " \t ")) assertEquals(blank, resolved(profile, env(blank)), profile);
        }
    }

    @Test
    @DisplayName("Spring, production profiles: a set value is used exactly as given (tidying happens later, in the link rules)")
    public void setValuesResolveAsGiven() {
        for (String profile : List.of("prod", "production")) {
            assertEquals("https://staging.example.org/", resolved(profile, env("https://staging.example.org/")), profile);
            assertEquals("http://localhost:3000", resolved(profile, env("http://localhost:3000")), profile);
        }
    }

    // ── what the production application does with each ─────────────────────────────────────────────

    @Test
    @DisplayName("Production start, variable absent: starts; the link is the production login page")
    public void absentStarts() {
        for (String profile : List.of("prod", "production")) {
            assertEquals(null, startupFailure(profile, env(null)), profile);
            assertEquals(PRODUCTION + "/login", AccountInvitationService.loginUrlFor(resolved(profile, env(null))), profile);
        }
    }

    @Test
    @DisplayName("Production start, variable present but empty or whitespace: the application REFUSES to start with a clear FRONTEND_BASE_URL error")
    public void emptyAndWhitespaceFailStartup() {
        for (String profile : List.of("prod", "production")) {
            for (String blank : List.of("", " ", "   ", "\t")) {
                String failure = startupFailure(profile, env(blank));
                assertNotNull(failure, profile + " '" + blank + "' must not start");
                assertTrue(failure.startsWith("Production startup failed: FRONTEND_BASE_URL is invalid: it is empty"), failure);
                assertTrue(failure.contains("remove the variable to use the production default"), failure);
            }
        }
    }

    @Test
    @DisplayName("Production start, valid HTTPS override: starts and links to it (slashes tidied)")
    public void validOverrideStarts() {
        for (String profile : List.of("prod", "production")) {
            Map<String, Object> variables = env("  https://staging.example.org///  ");
            assertEquals(null, startupFailure(profile, variables), profile);
            assertEquals("https://staging.example.org/login", AccountInvitationService.loginUrlFor(resolved(profile, variables)), profile);
        }
    }

    @Test
    @DisplayName("Production start, http / localhost / loopback / scheme-less override: the application REFUSES to start (never a localhost link)")
    public void invalidOverridesFailStartup() {
        for (String profile : List.of("prod", "production")) {
            for (String bad : List.of("http://localhost:3000", "https://localhost", "https://127.0.0.1", "http://taskotel.vercel.app", "taskotel.vercel.app", "https://")) {
                String failure = startupFailure(profile, env(bad));
                assertNotNull(failure, profile + " '" + bad + "' must not start");
                assertTrue(failure.contains("FRONTEND_BASE_URL is invalid"), failure);
            }
        }
    }

    @Test
    @DisplayName("No production path can produce a localhost invitation link: whatever the variable holds, a start that succeeds links to a clean https address")
    public void noProductionPathProducesALocalhostLink() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("absent", null);
        for (String v : List.of("", " ", "\t", "http://localhost:3000", "https://localhost:3000", "https://127.0.0.1", "0.0.0.0", "localhost:3000", "https://[::1]",
                "https://staging.example.org", "https://staging.example.org/", PRODUCTION, "ftp://x.example.org", "javascript:alert(1)")) cases.put("'" + v + "'", v);
        int started = 0;
        for (String profile : List.of("prod", "production")) {
            for (Map.Entry<String, String> c : cases.entrySet()) {
                Map<String, Object> variables = env(c.getValue());
                if (startupFailure(profile, variables) == null) {
                    started++;
                    String link = AccountInvitationService.loginUrlFor(resolved(profile, variables));
                    String lower = link.toLowerCase();
                    assertTrue(link.startsWith("https://") && link.endsWith("/login"), profile + " " + c.getKey() + " -> " + link);
                    assertFalse(lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0") || lower.contains("::1"), link);
                }
            }
        }
        assertEquals(2 * 4, started, "only: absent, https://staging.example.org (with and without a slash) and the production address, in both profiles");
    }

    @Test
    @DisplayName("Several active profiles: the production check and the production default apply whenever prod OR production is among them")
    public void severalActiveProfiles() {
        try (ConfigurableApplicationContext context = start(ReadOnlyConfig.class, "prod,staging", env(null))) {
            assertEquals(PRODUCTION, context.getEnvironment().getProperty("taskotel.frontend-base-url"));
        }
        for (String profiles : List.of("prod,staging", "staging,production", "production,prod")) {
            assertEquals(null, startupFailure(profiles, env(null)), profiles);
            String failure = startupFailure(profiles, env("http://localhost:3000"));
            assertNotNull(failure, profiles + " must not start with a localhost address");
            assertTrue(failure.contains("FRONTEND_BASE_URL is invalid"), failure);
        }
        // a profile that is neither prod nor production is not production: no check, the development default (this is why the Dockerfile fixes SPRING_PROFILES_ACTIVE=prod)
        assertEquals(LOCAL, resolved("staging", env(null)));
        assertEquals(null, startupFailure("staging", env("http://localhost:3000")));
    }

    // ── local development is unchanged ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Spring, no production profile: the local default, valid local overrides, and no startup check")
    public void localDevelopmentIsUnchanged() {
        assertEquals(LOCAL, resolved(null, env(null)));
        assertEquals(LOCAL + "/login", AccountInvitationService.loginUrlFor(resolved(null, env(null))));
        assertEquals("http://localhost:5173/", resolved(null, env("http://localhost:5173/")));
        assertEquals("http://localhost:5173/login", AccountInvitationService.loginUrlFor(resolved(null, env("http://localhost:5173/"))));
        // the check is a production-only bean: local development is never blocked, whatever the value
        assertEquals(null, startupFailure(null, env("not-a-url")));
        assertEquals(null, startupFailure("dev", env("")));
        // and the development fallback for an unusable value is the local default, as before
        assertEquals(LOCAL + "/login", AccountInvitationService.loginUrlFor(resolved(null, env(""))));
    }
}
