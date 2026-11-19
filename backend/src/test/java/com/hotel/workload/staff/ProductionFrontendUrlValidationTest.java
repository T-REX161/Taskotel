package com.hotel.workload.staff;

import com.hotel.workload.config.FrontendBaseUrl;
import com.hotel.workload.config.ProductionFrontendUrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production never links an email to localhost or to a broken address: an absent FRONTEND_BASE_URL gets the production default, a valid one is
 * used (slashes tidied), and an empty, malformed, http or local-machine value stops the production application from starting. The real
 * configuration files are resolved exactly as Spring does (see FrontendBaseUrlConfigTest), then the real startup check and the real invitation
 * link rules are applied. Local development keeps its localhost default and is never blocked.
 */
public class ProductionFrontendUrlValidationTest {

    private static final String PRODUCTION = "https://taskotel.vercel.app";
    private static final String[] PROFILE_FILES = {"application-prod.yml", "application-production.yml"};

    private static final List<String> INVALID = List.of("", "   ", "taskotel.vercel.app", "http://taskotel.vercel.app", "ftp://taskotel.vercel.app",
            "javascript:alert(1)", "https://", "http://localhost:3000", "https://localhost", "https://localhost:3000/", "https://sub.localhost",
            "https://127.0.0.1", "https://127.0.0.1:8080", "https://0.0.0.0", "https://[::1]", "https://user:pass@taskotel.vercel.app",
            "https://taskotel.vercel.app?x=1", "https://taskotel.vercel.app#frag", "https://exa mple.com", "https://a\"b.com", "https://a<b.com",
            // other spellings of the local machine and of private networks (all found by probing the real parser)
            "https://localhost.", "https://foo.localhost.", "https://localhost.localdomain", "https://LOCALHOST", "https://[0:0:0:0:0:0:0:1]", "https://[::]",
            "https://[::ffff:127.0.0.1]", "https://[::ffff:7f00:1]", "https://[fe80::1]", "https://[fc00::1]", "https://[fd12:3456::1]",
            "https://2130706433", "https://0", "https://0177.0.0.1", "https://127.1", "https://0x7f.0.0.1", "https://0.0.0.0", "https://10.0.0.5",
            "https://192.168.1.10", "https://172.16.0.1", "https://169.254.169.254", "https://100.64.0.1", "https://example.com:0", "https://example.com:99999",
            "https://exa%6Dple.com", "https://example.com\\evil.com", "HTTPS://Taskotel.vercel.app");

    private static String problem(String value) {
        return FrontendBaseUrl.productionProblem(value);
    }

    private static void startsInProduction(String resolved) {
        new ProductionFrontendUrlValidator(resolved).afterPropertiesSet();
    }

    // ── the rules themselves ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid production addresses pass: https, a real host, optional port and path, trailing slashes and spaces tolerated")
    public void validAddressesPass() {
        for (String ok : List.of(PRODUCTION, PRODUCTION + "/", "  " + PRODUCTION + "///  ", "https://app.example.org:8443", "https://example.com/taskotel/", "https://xn--bcher-kva.example")) {
            assertNull(problem(ok), ok);
        }
    }

    @Test
    @DisplayName("Legitimate production addresses are NOT refused: staging and sub-domains, hyphens, long suffixes, ports, paths, punycode, public IP literals")
    public void legitimateAddressesAreAccepted() {
        for (String ok : List.of("https://staging.taskotel.example.org", "https://app-2.eu.example.co.uk", "https://taskotel.vercel.app:443", "https://example.com:8443/taskotel",
                "https://xn--bcher-kva.example", "https://8.8.8.8", "https://[2606:4700:4700::1111]", "https://a.b.c.d.example.technology", "https://localhost-style.example.com",
                "https://mylocalhost.example.com", "https://taskotel.vercel.app/%2e%2e/")) {
            assertNull(problem(ok), ok);
        }
    }

    @Test
    @DisplayName("Empty, malformed, http, local-machine, private-network, ambiguous-numeric, credential, query and fragment addresses are all refused for production")
    public void invalidAddressesAreRefused() {
        for (String bad : INVALID) assertNotNull(problem(bad), "should be refused: '" + bad + "'");
        assertNotNull(problem(null));
    }

    @Test
    @DisplayName("The refusal message names FRONTEND_BASE_URL and the fix, and never echoes the value")
    public void messageIsClearAndSafe() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> startsInProduction("https://user:secret-pass@taskotel.vercel.app"));
        assertTrue(e.getMessage().startsWith("Production startup failed: FRONTEND_BASE_URL is invalid:"), e.getMessage());
        assertTrue(e.getMessage().contains("https address") && e.getMessage().contains("remove the variable"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-pass"), "the value is never echoed");
    }

    @Test
    @DisplayName("The development rules are unchanged: an unusable value still falls back to the local default, and the local default and overrides work")
    public void developmentRulesUnchanged() throws IOException {
        assertEquals("http://localhost:3000/login", AccountInvitationService.loginUrlFor(FrontendBaseUrlConfigTest.baseUrl(null, Map.of())));
        assertEquals("http://localhost:5173/login", AccountInvitationService.loginUrlFor(FrontendBaseUrlConfigTest.baseUrl(null, Map.of("FRONTEND_BASE_URL", "http://localhost:5173/"))));
        assertEquals("https://dev.example.org/login", AccountInvitationService.loginUrlFor(FrontendBaseUrlConfigTest.baseUrl(null, Map.of("FRONTEND_BASE_URL", "https://dev.example.org//"))));
        assertEquals("http://localhost:3000/login", AccountInvitationService.loginUrlFor("not a url"), "development only: a bad local value falls back, with a warning");
    }

    // ── the real configuration files, every case ────────────────────────────────────────────

    @Test
    @DisplayName("Production, FRONTEND_BASE_URL absent: starts, with the production default (both profile files)")
    public void productionDefaultStarts() throws IOException {
        for (String profile : PROFILE_FILES) {
            String resolved = FrontendBaseUrlConfigTest.baseUrl(profile, Map.of());
            startsInProduction(resolved);
            assertEquals(PRODUCTION + "/login", AccountInvitationService.loginUrlFor(resolved), profile);
        }
    }

    @Test
    @DisplayName("Production, valid FRONTEND_BASE_URL: starts and uses it, slashes tidied consistently (both profile files)")
    public void productionValidOverrideStarts() throws IOException {
        for (String profile : PROFILE_FILES) {
            for (String[] c : new String[][] {{"https://staging.example.org", "https://staging.example.org/login"}, {"https://staging.example.org/", "https://staging.example.org/login"},
                    {"  https://staging.example.org///  ", "https://staging.example.org/login"}, {"https://example.com/taskotel/", "https://example.com/taskotel/login"}}) {
                String resolved = FrontendBaseUrlConfigTest.baseUrl(profile, Map.of("FRONTEND_BASE_URL", c[0]));
                startsInProduction(resolved);
                assertEquals(c[1], AccountInvitationService.loginUrlFor(resolved), profile + " " + c[0]);
            }
        }
    }

    @Test
    @DisplayName("Production, invalid FRONTEND_BASE_URL (empty, no scheme, http, localhost, ...): the application refuses to start (both profile files)")
    public void productionInvalidOverrideFailsStartup() throws IOException {
        for (String profile : PROFILE_FILES) {
            for (String bad : INVALID) {
                String resolved = FrontendBaseUrlConfigTest.baseUrl(profile, Map.of("FRONTEND_BASE_URL", bad));
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> startsInProduction(resolved), profile + " '" + bad + "'");
                assertTrue(e.getMessage().contains("FRONTEND_BASE_URL"), e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("No production path can produce a localhost invitation link: every start that succeeds yields a clean https link, every other value stops the start")
    public void noProductionPathProducesALocalhostLink() throws IOException {
        Map<String, Map<String, Object>> environments = new LinkedHashMap<>();
        environments.put("absent", Map.of());
        for (String value : INVALID) environments.put("'" + value + "'", Map.of("FRONTEND_BASE_URL", value));
        for (String value : List.of(PRODUCTION, "https://staging.example.org/")) environments.put("'" + value + "'", Map.of("FRONTEND_BASE_URL", value));
        int started = 0;
        for (String profile : PROFILE_FILES) {
            for (Map.Entry<String, Map<String, Object>> env : environments.entrySet()) {
                String resolved = FrontendBaseUrlConfigTest.baseUrl(profile, env.getValue());
                boolean startupFails;
                try {
                    startsInProduction(resolved);
                    startupFails = false;
                } catch (IllegalStateException e) {
                    startupFails = true;
                }
                if (!startupFails) {
                    started++;
                    String link = AccountInvitationService.loginUrlFor(resolved);
                    assertTrue(link.startsWith("https://") && link.endsWith("/login"), profile + " " + env.getKey() + " -> " + link);
                    String lower = link.toLowerCase();
                    assertFalse(lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0") || lower.contains("::1"), link);
                }
            }
        }
        assertEquals(PROFILE_FILES.length * 3, started, "only the absent variable and the two valid overrides start");
    }

    // ── the real Spring wiring ──────────────────────────────────────────────────────────

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ProductionFrontendUrlValidator.class);

    @Test
    @DisplayName("Spring: in the production profiles an invalid address fails the context with the clear message; a valid one starts; the check is on in both aliases")
    public void springContextFailsOnlyInProduction() {
        for (String profile : List.of("prod", "production")) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "taskotel.frontend-base-url=https://localhost:3000")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                        assertThat(rootMessage(context.getStartupFailure())).contains("FRONTEND_BASE_URL is invalid").contains("local machine");
                    });
            runner.withPropertyValues("spring.profiles.active=" + profile, "taskotel.frontend-base-url=" + PRODUCTION)
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductionFrontendUrlValidator.class));
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasFailed());   // the property is missing altogether: never guessed
        }
    }

    @Test
    @DisplayName("Spring: local development is never blocked, whatever the value (the check does not exist outside production)")
    public void springDevelopmentIsNotBlocked() {
        for (String profile : List.of("dev", "default")) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "taskotel.frontend-base-url=http://localhost:3000")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionFrontendUrlValidator.class));
            runner.withPropertyValues("spring.profiles.active=" + profile, "taskotel.frontend-base-url=not-a-url")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
