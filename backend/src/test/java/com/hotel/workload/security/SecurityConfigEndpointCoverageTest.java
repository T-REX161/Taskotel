package com.hotel.workload.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Deny by default, proven against the real application (Step 2I).
 *
 * <p>{@link #EXPECTED} is the explicit authorization inventory: every API route and method with the roles allowed to call it. The test
 * reads the routes the controllers really map and fails in both directions: a route with no entry here (so a new endpoint cannot be
 * added without deciding who may call it) and an entry whose route no longer exists.
 *
 * <p>This test looks at the <em>URL layer</em> only. A refusal there has an empty body (401 from the entry point, 403 from the access
 * denied handler); a refusal by a service carries a JSON message. So "allowed" means "not refused by the URL layer": the request may still
 * end in 400, 404 or a service 403, and the services have their own tests (department isolation, direct service authorization).
 * Every test rolls back.
 */
@SpringBootTest
@Transactional
public class SecurityConfigEndpointCoverageTest {

    private static final String PUBLIC = "PUBLIC";
    private static final Set<String> ALL = Set.of("ADMIN", "MANAGER", "STAFF");
    private static final Set<String> AM = Set.of("ADMIN", "MANAGER");
    private static final Set<String> A = Set.of("ADMIN");
    private static final Set<String> NOBODY_SIGNED_IN = Set.of(PUBLIC);

    /** "METHOD /pattern" (path variables written as {}) to the roles that may call it, or PUBLIC. */
    private static final Map<String, Set<String>> EXPECTED = new TreeMap<>();

    private static void allow(Set<String> roles, String method, String... paths) {
        for (String path : paths) {
            EXPECTED.put(method + " " + path, roles);
        }
    }

    static {
        // Public: sign-in, onboarding and password reset
        allow(NOBODY_SIGNED_IN, "POST", "/api/auth/login", "/api/auth/email-verification/send", "/api/auth/email-verification/verify",
                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm");
        allow(NOBODY_SIGNED_IN, "PUT", "/api/auth/credentials/setup");
        // Any signed-in user, own data
        allow(ALL, "GET", "/api/profile", "/api/notification-preferences");
        allow(ALL, "PUT", "/api/profile/display-name", "/api/profile/phone", "/api/profile/preferences", "/api/profile/password",
                "/api/notification-preferences");
        allow(ALL, "POST", "/api/profile/picture", "/api/profile/email-change/request", "/api/profile/email-change/confirm");
        allow(ALL, "DELETE", "/api/profile/picture");
        // Users and Manager departments: Admin only
        allow(A, "GET", "/api/users", "/api/users/{}/manager-department", "/api/users/{}/manager-department/history");
        allow(A, "PUT", "/api/users/{}/manager-department");
        allow(A, "PATCH", "/api/users/{}/deactivate", "/api/users/{}/activate");
        allow(A, "DELETE", "/api/users/{}");
        // Staff
        allow(AM, "GET", "/api/staff", "/api/staff/username-availability");
        allow(AM, "POST", "/api/staff");
        allow(AM, "PUT", "/api/staff/{}");
        allow(AM, "POST", "/api/staff/{}/email-change");
        allow(AM, "PATCH", "/api/staff/{}/deactivate", "/api/staff/{}/activate");
        allow(A, "DELETE", "/api/staff/{}");
        // Tasks and assignments
        allow(ALL, "GET", "/api/tasks", "/api/assignments");
        allow(AM, "POST", "/api/tasks", "/api/assignments");
        allow(AM, "PUT", "/api/tasks/{}", "/api/assignments/{}");
        allow(AM, "DELETE", "/api/tasks/{}");
        allow(Set.of("STAFF"), "PATCH", "/api/tasks/{}/status");
        // System settings
        allow(A, "GET", "/api/system-settings");
        allow(A, "PUT", "/api/system-settings");
        // Workload and dashboard
        allow(ALL, "GET", "/api/workload", "/api/dashboard/summary");
        allow(AM, "GET", "/api/workload/recommendations");
        // Departments
        allow(ALL, "GET", "/api/departments");
        allow(A, "POST", "/api/departments");
        allow(A, "PUT", "/api/departments/{}");
        allow(A, "DELETE", "/api/departments/{}");
        // Cross-department support
        allow(A, "POST", "/api/support/assignments");
        allow(AM, "GET", "/api/support/options", "/api/support/assignments");
        allow(AM, "POST", "/api/support/preview", "/api/support/assignments/{}/archive", "/api/support/assignments/{}/end");
        // Development-only probes (present because the tests do not run under the production profile)
        allow(A, "GET", "/api/test/admin");
        allow(AM, "GET", "/api/test/manager");
        allow(ALL, "GET", "/api/test/staff");
    }

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "STAFF");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        // Start from an anonymous thread: another test class may have left a signed-in user in the security context
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String normalize(String pattern) {
        return pattern.replaceAll("\\{[^}/]+}", "{}");
    }

    private static String concrete(String normalizedPattern) {
        return normalizedPattern.replace("{}", UUID.randomUUID().toString());
    }

    /** Every API route the controllers map, as "METHOD /normalized-pattern". */
    private Set<String> mappedRoutes() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api")) continue;
                Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
                assertFalse(methods.isEmpty(), "Route " + pattern + " has no explicit HTTP method; map each method on its own so it can be authorized");
                for (org.springframework.web.bind.annotation.RequestMethod method : methods) {
                    routes.add(method.name() + " " + normalize(pattern));
                }
            }
        }
        return routes;
    }

    private MockHttpServletRequestBuilder request(String method, String path) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    private MvcResult call(String method, String path, String role) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path);
        if (role != null) {
            builder.with(user("coverage_" + role.toLowerCase()).roles(role));
        }
        return mockMvc.perform(builder).andReturn();
    }

    /** Refused by the URL layer: 401 or 403 with an empty body. A service refusal carries a JSON message and is not this. */
    private static boolean refusedByUrlLayer(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        return (status == 401 || status == 403) && result.getResponse().getContentAsString().isEmpty();
    }

    /**
     * For a public route: the URL layer let the request in. The sign-in handler itself answers a bad login with a bare 401, so for that
     * one route only a 403 counts as refused.
     */
    private static boolean publicRouteRefused(String routeKey, MvcResult result) throws Exception {
        if (routeKey.equals("POST /api/auth/login")) {
            return result.getResponse().getStatus() == 403 && result.getResponse().getContentAsString().isEmpty();
        }
        return refusedByUrlLayer(result);
    }

    private static String describe(MvcResult result) throws Exception {
        return result.getResponse().getStatus() + " body=[" + result.getResponse().getContentAsString() + "]";
    }

    // ── 1. The inventory matches the real routes ─────────────────────────────

    @Test
    @DisplayName("Every mapped API route has an explicit entry in the authorization inventory, and every entry is a real route")
    public void inventoryMatchesMappedRoutes() {
        Set<String> mapped = mappedRoutes();
        Set<String> missing = new TreeSet<>(mapped);
        missing.removeAll(EXPECTED.keySet());
        Set<String> stale = new TreeSet<>(EXPECTED.keySet());
        stale.removeAll(mapped);
        assertTrue(missing.isEmpty(), "Routes with no authorization decision (add them to SecurityConfig AND to this inventory): " + missing);
        assertTrue(stale.isEmpty(), "Inventory entries with no matching route: " + stale);
    }

    // ── 2. Each route, each role ─────────────────────────────────────────────

    @Test
    @DisplayName("Anonymous: 401 on every protected route; the public routes are not refused by the URL layer")
    public void anonymousAccess() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : EXPECTED.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            MvcResult result = call(parts[0], concrete(parts[1]), null);
            boolean isPublic = entry.getValue().contains(PUBLIC);
            if (isPublic && publicRouteRefused(entry.getKey(), result)) {
                problems.add(entry.getKey() + " is public but was refused: " + describe(result));
            }
            if (!isPublic && !(result.getResponse().getStatus() == 401 && refusedByUrlLayer(result))) {
                problems.add(entry.getKey() + " should be 401 for an anonymous caller but was " + describe(result));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("Signed-in roles: refused (403) exactly where the inventory says so, never refused where it allows them")
    public void roleAccess() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String role : ROLES) {
            for (Map.Entry<String, Set<String>> entry : EXPECTED.entrySet()) {
                String[] parts = entry.getKey().split(" ", 2);
                MvcResult result = call(parts[0], concrete(parts[1]), role);
                Set<String> allowed = entry.getValue();
                boolean mayCall = allowed.contains(PUBLIC) || allowed.contains(role);
                boolean refused = allowed.contains(PUBLIC) ? publicRouteRefused(entry.getKey(), result) : refusedByUrlLayer(result);
                if (mayCall && refused) {
                    problems.add(role + " should be allowed through the URL layer on " + entry.getKey() + " but got " + describe(result));
                }
                if (!mayCall && !(result.getResponse().getStatus() == 403 && refused)) {
                    problems.add(role + " should get 403 from the URL layer on " + entry.getKey() + " but got " + describe(result));
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // ── 3. Unknown routes and unlisted methods ───────────────────────────────

    @Test
    @DisplayName("Unknown API routes: 401 without a sign-in, 403 for every role (Admin included); never 404 or 200")
    public void unknownRoutesAreDenied() throws Exception {
        List<String> unknown = List.of("/api", "/api/", "/api/unknown", "/api/unknown/deeper/path", "/api/users/unknown/extra",
                "/api/staff/" + UUID.randomUUID() + "/unknown", "/api/auth/unknown", "/api/auth/login/extra", "/api/test/other",
                "/api/support", "/api/support/unknown", "/api/workload/unknown", "/api/dashboard", "/api/dashboard/other",
                "/api/profile/unknown", "/api/notification-preferences/unknown", "/api/admin", "/api/actuator/health", "/api/error");
        List<String> problems = new ArrayList<>();
        for (String path : unknown) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                MvcResult anonymous = call(method, path, null);
                if (!(anonymous.getResponse().getStatus() == 401 && refusedByUrlLayer(anonymous))) {
                    problems.add("anonymous " + method + " " + path + " -> " + describe(anonymous));
                }
                for (String role : ROLES) {
                    MvcResult signedIn = call(method, path, role);
                    if (!(signedIn.getResponse().getStatus() == 403 && refusedByUrlLayer(signedIn))) {
                        problems.add(role + " " + method + " " + path + " -> " + describe(signedIn));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("A method that is not listed for a route is refused (401 anonymous, 403 signed in, Admin included), HEAD and OPTIONS too")
    public void unlistedMethodsAreDenied() throws Exception {
        List<String> problems = new ArrayList<>();
        Set<String> routes = EXPECTED.keySet();
        Set<String> patterns = new TreeSet<>();
        for (String route : routes) patterns.add(route.split(" ", 2)[1]);
        for (String pattern : patterns) {
            String path = concrete(pattern);
            for (String method : METHODS) {
                if (routes.contains(method + " " + pattern)) continue;
                // HEAD is authorized exactly where GET is (SecurityConfig.allowGet); headMirrorsGetExactly checks it for every GET route
                if (method.equals("HEAD") && routes.contains("GET " + pattern)) continue;
                // A pattern such as /api/staff/{} also matches the literal route /api/staff/username-availability: skip methods that
                // the other route lists for this concrete path
                String effective = method.equals("HEAD") ? "GET" : method;
                boolean listedByAnotherPattern = routes.stream().anyMatch(r -> r.startsWith(effective + " ")
                        && new org.springframework.util.AntPathMatcher().match(r.split(" ", 2)[1].replace("{}", "*"), path));
                if (listedByAnotherPattern) continue;
                MvcResult anonymous = call(method, path, null);
                if (!(anonymous.getResponse().getStatus() == 401 && refusedByUrlLayer(anonymous))) {
                    problems.add("anonymous " + method + " " + pattern + " -> " + describe(anonymous));
                }
                for (String role : ROLES) {
                    MvcResult signedIn = call(method, path, role);
                    if (!(signedIn.getResponse().getStatus() == 403 && refusedByUrlLayer(signedIn))) {
                        problems.add(role + " " + method + " " + pattern + " -> " + describe(signedIn));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("HEAD is allowed exactly where GET is, for exactly the same roles: refused and allowed alike, for every GET route")
    public void headMirrorsGetExactly() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, Set<String>> entry : EXPECTED.entrySet()) {
            if (!entry.getKey().startsWith("GET ")) continue;
            String pattern = entry.getKey().split(" ", 2)[1];
            String path = concrete(pattern);
            for (String role : java.util.Arrays.asList(null, "ADMIN", "MANAGER", "STAFF", "GUEST")) {
                MvcResult get = role == null ? call("GET", path, null)
                        : mockMvc.perform(request("GET", path).with(user("coverage_head").roles(role))).andReturn();
                MvcResult head = role == null ? call("HEAD", path, null)
                        : mockMvc.perform(request("HEAD", path).with(user("coverage_head").roles(role))).andReturn();
                checked++;
                if (refusedByUrlLayer(get) != refusedByUrlLayer(head) || (refusedByUrlLayer(get) && get.getResponse().getStatus() != head.getResponse().getStatus())) {
                    problems.add(role + " " + entry.getKey() + ": GET -> " + describe(get) + " but HEAD -> " + describe(head));
                }
                // Spelled out against the inventory too, so a mistake in GET itself cannot hide a matching mistake in HEAD
                boolean mayCall = role != null && entry.getValue().contains(role);
                if (role != null && mayCall == refusedByUrlLayer(head)) {
                    problems.add(role + " HEAD " + pattern + " should " + (mayCall ? "be allowed" : "be refused") + " but got " + describe(head));
                }
                if (role == null && !(head.getResponse().getStatus() == 401 && refusedByUrlLayer(head))) {
                    problems.add("anonymous HEAD " + pattern + " should be 401 but got " + describe(head));
                }
            }
        }
        assertTrue(checked > 0);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("HEAD is refused on every route that has no GET, and OPTIONS everywhere (nothing in the app uses either)")
    public void headWithoutGetAndOptionsAreRefused() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String route : EXPECTED.keySet()) {
            String[] parts = route.split(" ", 2);
            String pattern = parts[1];
            String path = concrete(pattern);
            boolean hasGet = EXPECTED.containsKey("GET " + pattern);
            List<String> refused = hasGet ? List.of("OPTIONS") : List.of("HEAD", "OPTIONS");
            for (String method : refused) {
                for (String role : ROLES) {
                    MvcResult result = call(method, path, role);
                    if (!(result.getResponse().getStatus() == 403 && refusedByUrlLayer(result))) {
                        problems.add(role + " " + method + " " + pattern + " -> " + describe(result));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("A role that is not Admin, Manager or Staff is refused everywhere that needs a sign-in")
    public void unknownRoleIsRefused() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : EXPECTED.entrySet()) {
            if (entry.getValue().contains(PUBLIC)) continue;
            String[] parts = entry.getKey().split(" ", 2);
            MvcResult result = mockMvc.perform(request(parts[0], concrete(parts[1])).with(user("coverage_other").roles("GUEST"))).andReturn();
            if (!(result.getResponse().getStatus() == 403 && refusedByUrlLayer(result))) {
                problems.add("GUEST " + entry.getKey() + " -> " + describe(result));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
