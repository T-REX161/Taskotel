package com.hotel.workload.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.hotel.workload.verification.EmailService;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Response-stability guard for the multi-department Manager change (P0.5). It records, from the code as it was BEFORE that change, what
 * the API answers to an Admin, to a Staff member and to a Manager with NO additional departments, and fails if a later change answers
 * any of those requests differently.
 *
 * <p>The guarantee is <b>equivalent normalized responses</b> (same status code and the same JSON after normalization), not byte-identical
 * responses. Normalization: object keys are sorted; fixture ids become stable labels (for example {@code <staff:rs_staff_a1>}) and any other
 * UUID becomes {@code <uuid>}, also inside strings; date-times become {@code <datetime>}; dates become their distance from today
 * ({@code <today+1>}). Arrays keep the order the API sent and are compared in that order, EXCEPT the few listed in {@link #UNORDERED}, whose
 * order the API does not define (their query has no ORDER BY, and SQL leaves the order of such a result unspecified: it may differ between
 * runs, data sets and database engines): only those are compared as sorted lists. Every other value (numbers, booleans, texts, nulls, the presence of every field) must match.
 *
 * <p>The newest-first contracts of the history endpoints (and the other documented orders) are also asserted directly, with several
 * records, by the {@code ...Order...} tests below; the reference files hold only the fixture's (mostly empty) histories.
 *
 * <p>Runs on its own empty in-memory database, so data left by other test classes can never change what an Admin lists; every test
 * method rolls back. Reference files: {@code src/test/resources/regression/response-stability/}. To re-record them deliberately (only
 * with approval, and only when a change to these responses is intended): {@code mvnw test -Dtest=ResponseStabilityRegressionTest
 * -DresponseStability.record=true}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:responsestabilitydb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL"
})
@Transactional
public class ResponseStabilityRegressionTest {

    private static final String REFERENCE_DIR = "regression/response-stability";
    private static final boolean RECORD = Boolean.getBoolean("responseStability.record");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * The only arrays compared without regard to order: "METHOD /path" (ids as {id}, no query) mapped to JSON paths ("$" is the body, "[]"
     * an array element). Each is justified by the query behind it, which has no ORDER BY, so the order of its rows is unspecified (no
     * particular order, by id or otherwise, is guaranteed or assumed):
     * <ul>
     *   <li>GET /api/staff: {@code staffRepository.findAll()} (Admin) / {@code findByDepartmentAndUserRole} (Manager)</li>
     *   <li>GET /api/tasks: {@code taskRepository.findAll()} / {@code findByDepartment} / {@code findTasksByStaffId} (Staff)</li>
     *   <li>GET /api/users: {@code userRepository.findAll()}</li>
     *   <li>GET /api/workload: one row per staff member, in the order of {@code staffRepository.findAll()} / {@code findByDepartmentAndUserRole}</li>
     *   <li>GET /api/dashboard/summary {@code recentAssignments} (Staff): {@code assignmentRepository.findByStaffId}</li>
     * </ul>
     * Everything else is compared in order: assignments (assigned time, newest first), departments (created time, then name), every history
     * (newest first), support lists (newest first), capacity alerts and recommendations (explicit sorts), limitation notes (fixed order).
     * An array that is not listed here is ordered by default, so a new array is never silently relaxed.
     */
    static final Map<String, java.util.Set<String>> UNORDERED = Map.of(
            "GET /api/staff", java.util.Set.of("$"),
            "GET /api/tasks", java.util.Set.of("$"),
            "GET /api/users", java.util.Set.of("$"),
            "GET /api/workload", java.util.Set.of("$"),
            "GET /api/dashboard/summary", java.util.Set.of("$.recentAssignments"));

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;
    @Autowired private com.hotel.workload.support.SupportAssignmentRepository supportRepository;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private MockMvc mvc;

    private String admin;
    private String managerA;
    private String staffA1;
    private User managerAUser;
    private User managerBUser;
    private Staff staffA2;
    private Staff staffB1;
    private UUID taskAPending;
    private UUID taskBPending;
    private UUID assignmentA;
    private UUID assignmentB;

    /**
     * Two departments (RS_A, RS_B) next to the six built-in ones; an Admin; one Manager per department (neither has additional departments);
     * three Staff in RS_A (one unavailable) and one in RS_B; two waiting and two assigned tasks. Only rs_staff_a2 is free and available, so
     * no answer depends on choosing between equal candidates (the recommendation engine breaks such ties by name and then by id, and the
     * fixture's ids are generated afresh on every run).
     */
    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department a = fixtures.department("RS_A");
        Department b = fixtures.department("RS_B");
        label(a.getId(), "dept:RS_A");
        label(b.getId(), "dept:RS_B");

        User adminUser = fixtures.account("rs_admin", Role.ADMIN, true);
        label(adminUser.getId(), "user:rs_admin");
        Staff mgrA = fixtures.manager("rs_mgr_a", a);
        Staff mgrB = fixtures.manager("rs_mgr_b", b);
        managerAUser = mgrA.getUser();
        Staff a1 = fixtures.staff("rs_staff_a1", a);
        staffA2 = fixtures.staff("rs_staff_a2", a);
        Staff a3 = fixtures.staff("rs_staff_a3", a);
        a3.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(a3);
        staffB1 = fixtures.staff("rs_staff_b1", b);
        for (Staff s : List.of(mgrA, mgrB, a1, staffA2, a3, staffB1)) {
            label(s.getId(), "staff:" + s.getUser().getUsername());
            label(s.getUser().getId(), "user:" + s.getUser().getUsername());
        }

        admin = "Bearer " + jwt.generateToken("rs_admin", Role.ADMIN);
        managerA = "Bearer " + jwt.generateToken("rs_mgr_a", Role.MANAGER);
        staffA1 = "Bearer " + jwt.generateToken("rs_staff_a1", Role.STAFF);

        taskAPending = createTask("RS A pending", "RS_A", "HIGH", 30, 1);
        UUID taskAAssigned = createTask("RS A assigned", "RS_A", "MEDIUM", 45, 2);
        taskBPending = createTask("RS B pending", "RS_B", "URGENT", 60, 0);
        UUID taskBAssigned = createTask("RS B assigned", "RS_B", "LOW", 20, 3);
        managerBUser = mgrB.getUser();
        assignmentA = assign(taskAAssigned, a1.getId(), "assignment:RS A assigned");
        Thread.sleep(20); // assignments are listed newest first by assigned time alone: keep the two times apart, so their order is defined
        assignmentB = assign(taskBAssigned, staffB1.getId(), "assignment:RS B assigned");
    }

    // ── Admin ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin: every read answers as before (equivalent normalized responses)")
    void adminReads() throws Exception {
        String mgr = "/api/users/" + managerAUser.getId();
        check("admin", HttpMethod.GET, "/api/staff", admin, null);
        check("admin", HttpMethod.GET, "/api/staff/username-availability?username=rs_new_person", admin, null);
        check("admin", HttpMethod.GET, "/api/tasks", admin, null);
        check("admin", HttpMethod.GET, "/api/assignments", admin, null);
        check("admin", HttpMethod.GET, "/api/workload", admin, null);
        check("admin", HttpMethod.GET, "/api/workload/departments", admin, null);
        check("admin", HttpMethod.GET, "/api/workload/recommendations", admin, null);
        check("admin", HttpMethod.GET, "/api/dashboard/summary", admin, null);
        check("admin", HttpMethod.GET, "/api/departments", admin, null);
        check("admin", HttpMethod.GET, "/api/support/cross-department-status", admin, null);
        check("admin", HttpMethod.GET, "/api/support/options?receivingDepartment=RS_B", admin, null);
        check("admin", HttpMethod.GET, "/api/support/assignments", admin, null);
        check("admin", HttpMethod.GET, "/api/users", admin, null);
        check("admin", HttpMethod.GET, "/api/system-settings", admin, null);
        check("admin", HttpMethod.GET, "/api/profile", admin, null);
        check("admin", HttpMethod.GET, mgr + "/manager-department", admin, null);
        check("admin", HttpMethod.GET, mgr + "/view-departments", admin, null);
        check("admin", HttpMethod.GET, mgr + "/view-departments/history", admin, null);
        check("admin", HttpMethod.GET, mgr + "/view-departments/history/page", admin, null);
    }

    @Test
    @DisplayName("Admin: writes answer as before (create a task in any department, reassign, deactivate, Manager refused on the grant endpoint)")
    void adminWrites() throws Exception {
        check("admin", HttpMethod.POST, "/api/tasks", admin, Map.of("title", "RS admin new", "priority", "MEDIUM", "estimatedDuration", 15,
                "dueDate", LocalDate.now().plusDays(4).toString(), "department", "RS_B"));
        check("admin", HttpMethod.PUT, "/api/assignments/" + assignmentA, admin, Map.of("staffId", staffA2.getId()));
        check("admin", HttpMethod.PATCH, "/api/staff/" + staffB1.getId() + "/deactivate", admin, null);
        check("admin", HttpMethod.POST, "/api/assignments", admin, Map.of("taskId", taskBPending, "staffId", staffA2.getId()));
    }

    // ── Staff ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff: own reads answer as before")
    void staffReads() throws Exception {
        check("staff", HttpMethod.GET, "/api/tasks", staffA1, null);
        check("staff", HttpMethod.GET, "/api/assignments", staffA1, null);
        check("staff", HttpMethod.GET, "/api/workload", staffA1, null);
        check("staff", HttpMethod.GET, "/api/workload/departments", staffA1, null);
        check("staff", HttpMethod.GET, "/api/dashboard/summary", staffA1, null);
        check("staff", HttpMethod.GET, "/api/departments", staffA1, null);
        check("staff", HttpMethod.GET, "/api/profile", staffA1, null);
    }

    @Test
    @DisplayName("Staff: everything outside their own work is refused as before")
    void staffRefusals() throws Exception {
        String mgr = "/api/users/" + managerAUser.getId();
        check("staff", HttpMethod.GET, "/api/staff", staffA1, null);
        check("staff", HttpMethod.GET, "/api/workload/recommendations", staffA1, null);
        check("staff", HttpMethod.GET, "/api/support/options?receivingDepartment=RS_A", staffA1, null);
        check("staff", HttpMethod.GET, "/api/support/assignments", staffA1, null);
        check("staff", HttpMethod.GET, "/api/users", staffA1, null);
        check("staff", HttpMethod.GET, "/api/system-settings", staffA1, null);
        check("staff", HttpMethod.GET, mgr + "/view-departments", staffA1, null);
        check("staff", HttpMethod.POST, "/api/tasks", staffA1, Map.of("title", "RS staff try", "priority", "LOW", "estimatedDuration", 10,
                "department", "RS_A"));
        check("staff", HttpMethod.POST, "/api/assignments", staffA1, Map.of("taskId", taskAPending, "staffId", staffA2.getId()));
        check("staff", HttpMethod.PATCH, "/api/staff/" + staffA2.getId() + "/deactivate", staffA1, null);
    }

    // ── Manager with no additional departments ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Manager without additional departments: reads answer as before (own department only)")
    void managerReads() throws Exception {
        check("manager", HttpMethod.GET, "/api/staff", managerA, null);
        check("manager", HttpMethod.GET, "/api/tasks", managerA, null);
        check("manager", HttpMethod.GET, "/api/assignments", managerA, null);
        check("manager", HttpMethod.GET, "/api/workload", managerA, null);
        check("manager", HttpMethod.GET, "/api/workload/departments", managerA, null);
        check("manager", HttpMethod.GET, "/api/workload/recommendations", managerA, null);
        check("manager", HttpMethod.GET, "/api/dashboard/summary", managerA, null);
        check("manager", HttpMethod.GET, "/api/departments", managerA, null);
        check("manager", HttpMethod.GET, "/api/support/cross-department-status", managerA, null);
        check("manager", HttpMethod.GET, "/api/support/options?receivingDepartment=RS_A", managerA, null);
        check("manager", HttpMethod.GET, "/api/support/assignments", managerA, null);
        check("manager", HttpMethod.GET, "/api/profile", managerA, null);
    }

    @Test
    @DisplayName("Manager without additional departments: own-department writes work, everything else is refused as before")
    void managerWritesAndRefusals() throws Exception {
        String self = "/api/users/" + managerAUser.getId();
        check("manager", HttpMethod.POST, "/api/tasks", managerA, Map.of("title", "RS mgr own", "priority", "LOW", "estimatedDuration", 10,
                "dueDate", LocalDate.now().plusDays(5).toString()));
        check("manager", HttpMethod.POST, "/api/tasks", managerA, Map.of("title", "RS mgr other", "priority", "LOW", "estimatedDuration", 10,
                "department", "RS_B"));
        check("manager", HttpMethod.POST, "/api/tasks", managerA, Map.of("title", "RS mgr unknown", "priority", "LOW", "estimatedDuration", 10,
                "department", "RS_NOPE"));
        check("manager", HttpMethod.POST, "/api/assignments", managerA, Map.of("taskId", taskBPending, "staffId", staffA2.getId()));
        check("manager", HttpMethod.POST, "/api/assignments", managerA, Map.of("taskId", taskAPending, "staffId", staffB1.getId()));
        check("manager", HttpMethod.PUT, "/api/assignments/" + assignmentB, managerA, Map.of("staffId", staffA2.getId()));
        check("manager", HttpMethod.PATCH, "/api/staff/" + staffB1.getId() + "/deactivate", managerA, null);
        check("manager", HttpMethod.DELETE, "/api/tasks/" + taskBPending, managerA, null);
        check("manager", HttpMethod.GET, "/api/support/options?receivingDepartment=RS_B", managerA, null);
        check("manager", HttpMethod.GET, "/api/users", managerA, null);
        check("manager", HttpMethod.GET, "/api/system-settings", managerA, null);
        check("manager", HttpMethod.GET, self + "/view-departments", managerA, null);
        check("manager", HttpMethod.PUT, self + "/view-departments", managerA, Map.of("departments", List.of("RS_B")));
        check("manager", HttpMethod.GET, self + "/view-departments/history/page", managerA, null);
        // The last one: a write in the Manager's own department that succeeds (it changes data the checks above read)
        check("manager", HttpMethod.POST, "/api/assignments", managerA, Map.of("taskId", taskAPending, "staffId", staffA2.getId()));
    }

    // ── the guard itself ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The normalizer: labels, dates and sorted keys; arrays keep their order unless the endpoint's order is undefined")
    void normalizerIsStrictOutsideVolatileFields() throws Exception {
        UUID known = UUID.randomUUID();
        label(known, "thing:x");
        JsonNode raw = json.readTree("{\"b\":[3,1,2],\"a\":\"" + known + "\",\"c\":\"" + UUID.randomUUID() + "\",\"d\":\"2026-01-02T03:04:05.678\","
                + "\"e\":\"" + LocalDate.now().plusDays(2) + "\",\"f\":\"text " + known + "\",\"g\":false,\"h\":null,\"i\":1.5}");
        // An endpoint with no unordered arrays: the array keeps the order it was sent in
        assertEquals("{\"a\":\"<thing:x>\",\"b\":[3,1,2],\"c\":\"<uuid>\",\"d\":\"<datetime>\",\"e\":\"<today+2>\",\"f\":\"text <thing:x>\","
                + "\"g\":false,\"h\":null,\"i\":1.5}", json.writeValueAsString(normalize(raw, "GET /api/assignments")));
        // A listed path is compared sorted, and only that path: an array inside its elements keeps its order
        assertEquals("[{\"k\":1,\"n\":[2,1]},{\"k\":2,\"n\":[2,1]}]",
                json.writeValueAsString(normalize(json.readTree("[{\"k\":2,\"n\":[2,1]},{\"k\":1,\"n\":[2,1]}]"), "GET /api/staff")));
        assertEquals("{\"recentAssignments\":[1,2],\"x\":[2,1]}",
                json.writeValueAsString(normalize(json.readTree("{\"x\":[2,1],\"recentAssignments\":[2,1]}"), "GET /api/dashboard/summary")));
        // Order, a changed value, a missing field and an extra field are all differences
        assertTrue(!normalize(json.readTree("[1,2]"), "GET /api/assignments").equals(normalize(json.readTree("[2,1]"), "GET /api/assignments")));
        assertTrue(!normalize(json.readTree("{\"g\":true}"), "GET /x").equals(normalize(json.readTree("{\"g\":false}"), "GET /x")));
        assertTrue(!normalize(json.readTree("{\"g\":true}"), "GET /x").equals(normalize(json.readTree("{\"g\":true,\"x\":1}"), "GET /x")));
        // Sorting an unordered list never hides a changed element
        assertTrue(!normalize(json.readTree("[1,2]"), "GET /api/staff").equals(normalize(json.readTree("[1,3]"), "GET /api/staff")));
    }

    // ── documented orders, asserted directly with several records ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Order: the access history (full and paged) lists the newest change first; pages neither overlap nor skip")
    void accessHistoryOrderNewestFirst() throws Exception {
        String base = "/api/users/" + managerAUser.getId() + "/view-departments";
        List<String> performed = new ArrayList<>();
        for (List<String> grants : List.of(List.of("RS_B"), List.<String>of(), List.of("RS_B"), List.of("RS_B", "HOUSEKEEPING"), List.of("HOUSEKEEPING"))) {
            Thread.sleep(20); // distinct change times, so "newest first" has a single right answer
            MvcResult put = call(HttpMethod.PUT, base, admin, Map.of("departments", grants));
            assertEquals(200, put.getResponse().getStatus(), put.getResponse().getContentAsString());
        }
        performed.addAll(List.of("GRANTED RS_B", "REVOKED RS_B", "GRANTED RS_B", "GRANTED HOUSEKEEPING", "REVOKED RS_B"));

        JsonNode full = body(call(HttpMethod.GET, base + "/history", admin, null));
        List<String> shown = new ArrayList<>();
        full.forEach(c -> shown.add(c.get("action").asText() + " " + c.get("departmentCode").asText()));
        List<String> newestFirst = new ArrayList<>(performed);
        java.util.Collections.reverse(newestFirst);
        assertEquals(newestFirst, shown, "the full history lists the newest change first");
        assertNonIncreasing(full, "changedAt");

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode p = body(call(HttpMethod.GET, base + "/history/page?size=2&page=" + page, admin, null));
            assertNonIncreasing(p.get("items"), "changedAt");
            p.get("items").forEach(c -> paged.add(c.get("id").asText()));
            assertEquals(page < 2, p.get("hasMore").asBoolean(), "page " + page);
        }
        List<String> fullIds = new ArrayList<>();
        full.forEach(c -> fullIds.add(c.get("id").asText()));
        assertEquals(fullIds, paged, "pages of 2 read in turn give exactly the full history, in the same order");

        JsonNode departments = body(call(HttpMethod.GET, base + "/history/page", admin, null)).get("departments");
        List<String> names = new ArrayList<>();
        departments.forEach(d -> names.add(d.get("name").asText()));
        List<String> sortedNames = new ArrayList<>(names);
        sortedNames.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(sortedNames, names, "the history's department filter is sorted by name");
        assertEquals(2, names.size());

        JsonNode grants = body(call(HttpMethod.GET, base, admin, null)).get("departments");
        assertEquals(1, grants.size());
    }

    @Test
    @DisplayName("Order: changes made in one save keep a stable order across pages (ties are broken by id)")
    void accessHistoryTiesAreStableAcrossPages() throws Exception {
        String base = "/api/users/" + managerAUser.getId() + "/view-departments";
        // One save that grants three departments records three changes at (nearly) the same moment
        assertEquals(200, call(HttpMethod.PUT, base, admin, Map.of("departments", List.of("RS_B", "HOUSEKEEPING", "SECURITY"))).getResponse().getStatus());
        Thread.sleep(20);
        assertEquals(200, call(HttpMethod.PUT, base, admin, Map.of("departments", List.of())).getResponse().getStatus());
        List<String> all = new ArrayList<>();
        body(call(HttpMethod.GET, base + "/history/page?size=100", admin, null)).get("items").forEach(c -> all.add(c.get("id").asText()));
        assertEquals(6, all.size());
        for (int size = 1; size <= 4; size++) {
            List<String> paged = new ArrayList<>();
            for (int page = 0; paged.size() < all.size() && page < 10; page++) {
                body(call(HttpMethod.GET, base + "/history/page?size=" + size + "&page=" + page, admin, null)).get("items")
                        .forEach(c -> paged.add(c.get("id").asText()));
            }
            assertEquals(all, paged, "page size " + size + " gives the same order, nothing repeated or skipped");
        }
        JsonNode full = body(call(HttpMethod.GET, base + "/history", admin, null));
        assertNonIncreasing(full, "changedAt");
        assertEquals(6, full.size());
    }

    @Test
    @DisplayName("Order: a Manager's department history lists the newest change first")
    void managerDepartmentHistoryOrderNewestFirst() throws Exception {
        String base = "/api/users/" + managerBUser.getId() + "/manager-department";
        for (String department : List.of("RS_A", "HOUSEKEEPING", "RS_B")) {
            Thread.sleep(20);
            MvcResult put = call(HttpMethod.PUT, base, admin, Map.of("department", department));
            assertEquals(200, put.getResponse().getStatus(), put.getResponse().getContentAsString());
        }
        JsonNode history = body(call(HttpMethod.GET, base + "/history", admin, null));
        assertEquals(3, history.size(), history.toString());
        assertNonIncreasing(history, "changedAt");
        assertTrue(history.get(0).toString().contains("RS_B") && history.get(2).toString().contains("RS_A"), history.toString());
    }

    @Test
    @DisplayName("Order: the support history lists the newest record first, for an Admin and for a Manager")
    void supportHistoryOrderNewestFirst() throws Exception {
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withNano(0);
        // Saved out of order on purpose: 1 hour ago, 3 hours ago, 2 hours ago
        for (int hoursAgo : new int[]{1, 3, 2}) {
            com.hotel.workload.support.SupportAssignment s = new com.hotel.workload.support.SupportAssignment();
            s.setPlanId(UUID.randomUUID());
            s.setStaffId(staffA2.getId());
            s.setStaffName("Scope rs_staff_a2");
            s.setHomeDepartmentCode("RS_A");
            s.setReceivingDepartmentCode("RS_B");
            s.setTaskId(taskBPending);
            s.setTaskTitle("RS B pending " + hoursAgo);
            s.setApprovedByUserId(UUID.randomUUID());
            s.setApprovedByUsername("rs_admin");
            s.setCreatedAt(now.minusHours(hoursAgo));
            s.end(com.hotel.workload.support.SupportStatus.COMPLETED, com.hotel.workload.support.SupportEndReason.TASK_COMPLETED, now);
            supportRepository.saveAndFlush(s);
        }
        for (String token : List.of(admin, managerA)) {
            JsonNode rows = body(call(HttpMethod.GET, "/api/support/assignments", token, null));
            assertEquals(3, rows.size(), rows.toString());
            List<String> created = new ArrayList<>();
            rows.forEach(r -> created.add(r.get("createdAt").asText()));
            assertEquals(List.of(now.minusHours(1), now.minusHours(2), now.minusHours(3)),
                    created.stream().map(text -> java.time.LocalDateTime.ofInstant(java.time.Instant.parse(text), java.time.ZoneId.systemDefault())).toList(), "newest first"); // sent as an instant with a zone
        }
    }

    @Test
    @DisplayName("Order: assignments newest first; departments by creation time, then name")
    void assignmentAndDepartmentOrder() throws Exception {
        for (String token : List.of(admin, managerA)) {
            assertNonIncreasing(body(call(HttpMethod.GET, "/api/assignments", token, null)), "assignedAt");
        }
        JsonNode adminAssignments = body(call(HttpMethod.GET, "/api/assignments", admin, null));
        assertEquals(assignmentB.toString(), adminAssignments.get(0).get("id").asText(), "the later assignment comes first");

        List<String> expected = new ArrayList<>();
        com.hotel.workload.staff.DepartmentCatalog.BUILT_IN.forEach(b -> expected.add(b.code()));
        expected.addAll(List.of("RS_A", "RS_B"));
        for (String token : List.of(admin, staffA1)) {
            List<String> codes = new ArrayList<>();
            body(call(HttpMethod.GET, "/api/departments", token, null)).forEach(d -> codes.add(d.get("code").asText()));
            assertEquals(expected, codes);
        }
        // A Manager receives only the departments they manage (here RS_A), whatever the hotel holds
        List<String> managerCodes = new ArrayList<>();
        body(call(HttpMethod.GET, "/api/departments", managerA, null)).forEach(d -> managerCodes.add(d.get("code").asText()));
        assertEquals(List.of("RS_A"), managerCodes);
    }

    /**
     * The department list's contract, not just the fixture's order: DepartmentServiceImpl.getAllDepartments maps
     * DepartmentRepository.findAllByOrderByCreatedAtAscNameAsc() one to one (ORDER BY created_at ASC, name ASC). Three departments are saved so
     * that saving order, creation time and name all disagree: the list must follow creation time, then name, and nothing else. The names are
     * single capitalized words, so their order is the same under any database collation.
     */
    @Test
    @DisplayName("Order: the department list follows creation time, then name (not saving order, code or name alone)")
    void departmentOrderIsCreationTimeThenName() throws Exception {
        java.time.LocalDateTime later = java.time.LocalDateTime.now().plusDays(1);
        java.time.LocalDateTime earliest = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);
        // Saved in this order: a late one named Zulu, an early one, then a late one named Alpha created at the very same moment as Zulu
        saveDepartment("RS_LATE_1", "Zulu", later);
        saveDepartment("RS_EARLY", "Mike", earliest);
        saveDepartment("RS_LATE_2", "Alpha", later);

        List<String> expected = new ArrayList<>();
        expected.add("RS_EARLY"); // created first of all, although saved second
        com.hotel.workload.staff.DepartmentCatalog.BUILT_IN.forEach(b -> expected.add(b.code())); // created at start-up, in catalog order
        expected.addAll(List.of("RS_A", "RS_B")); // the fixture's, created during setUp
        expected.addAll(List.of("RS_LATE_2", "RS_LATE_1")); // same creation time: by name, Alpha before Zulu, although Zulu was saved first
        for (String token : List.of(admin, staffA1)) {
            List<String> codes = new ArrayList<>();
            body(call(HttpMethod.GET, "/api/departments", token, null)).forEach(d -> codes.add(d.get("code").asText()));
            assertEquals(expected, codes);
        }
        List<String> managerCodes = new ArrayList<>();
        body(call(HttpMethod.GET, "/api/departments", managerA, null)).forEach(d -> managerCodes.add(d.get("code").asText()));
        assertEquals(List.of("RS_A"), managerCodes, "a Manager's list holds only their managed departments");
    }

    private void saveDepartment(String code, String name, java.time.LocalDateTime createdAt) {
        Department department = new Department(code, name);
        department.setCreatedAt(createdAt);
        departmentRepository.saveAndFlush(department);
    }

    private JsonNode body(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Each element's date-time field is not later than the one before it (newest first). */
    private static void assertNonIncreasing(JsonNode array, String field) {
        java.time.Instant previous = null; // the API sends every moment as an instant with a zone (UtcTimestamps)
        for (JsonNode element : array) {
            java.time.Instant value = java.time.Instant.parse(element.get(field).asText());
            if (previous != null) assertTrue(!value.isAfter(previous), field + " out of order: " + value + " after " + previous);
            previous = value;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private void label(UUID id, String name) {
        if (id != null) labels.put(id.toString().toLowerCase(), "<" + name + ">");
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private UUID createTask(String title, String department, String priority, int minutes, int dueInDays) throws Exception {
        MvcResult result = call(HttpMethod.POST, "/api/tasks", admin, Map.of("title", title, "priority", priority, "estimatedDuration", minutes,
                "dueDate", LocalDate.now().plusDays(dueInDays).toString(), "department", department));
        assertTrue(result.getResponse().getStatus() < 300, title + ": " + result.getResponse().getContentAsString());
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        label(id, "task:" + title);
        return id;
    }

    private UUID assign(UUID taskId, UUID staffId, String name) throws Exception {
        MvcResult result = call(HttpMethod.POST, "/api/assignments", admin, Map.of("taskId", taskId, "staffId", staffId));
        assertTrue(result.getResponse().getStatus() < 300, name + ": " + result.getResponse().getContentAsString());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        JsonNode first = body.isArray() ? body.get(0) : body;
        UUID id = UUID.fromString(first.get("id").asText());
        label(id, name);
        return id;
    }

    /** Calls the endpoint and compares (or, in record mode, writes) its normalized status and body. */
    private void check(String role, HttpMethod method, String url, String token, Object body) throws Exception {
        MvcResult result = call(method, url, token, body);
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectNode actual = JsonNodeFactory.instance.objectNode();
        String shownUrl = labelText(url);
        actual.put("request", method.name() + " " + shownUrl);
        actual.put("status", result.getResponse().getStatus());
        String endpoint = method.name() + " " + shownUrl.replaceAll("<[^>]+>", "{id}").replaceAll("\\?.*$", "");
        actual.set("body", content.isBlank() ? JsonNodeFactory.instance.nullNode() : normalize(parse(content), endpoint));
        String rendered = json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(actual).replace("\r\n", "\n") + "\n";
        String file = fileName(role, method, shownUrl, body);

        if (RECORD) {
            Path target = Path.of("src", "test", "resources", REFERENCE_DIR, file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, rendered, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(REFERENCE_DIR + "/" + file)) {
            assertNotNull(in, "No reference response for " + method + " " + shownUrl + " as " + role + " (" + file + ")");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(expected, rendered, "The " + role + " response to " + method + " " + shownUrl + " changed");
    }

    private JsonNode parse(String content) {
        try {
            return json.readTree(content);
        } catch (IOException notJson) {
            return TextNode.valueOf(content);
        }
    }

    /** role__METHOD_path[__body-hash].json: the request body is part of the name, so two writes to one URL get two files. */
    private String fileName(String role, HttpMethod method, String shownUrl, Object body) throws IOException {
        String path = shownUrl.replaceAll("<([a-z]+):([^>]+)>", "$1-$2").replaceAll("[^A-Za-z0-9-]+", "_").replaceAll("^_+|_+$", "");
        String suffix = body == null ? "" : "__" + Integer.toHexString(labelText(json.writeValueAsString(new TreeMap<>(asMap(body)))).hashCode());
        return role + "__" + method.name() + "_" + path + suffix + ".json";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object body) {
        Map<String, Object> copy = new TreeMap<>();
        ((Map<String, Object>) body).forEach((k, v) -> copy.put(k, v == null ? null : v.toString().matches("\\d{4}-\\d{2}-\\d{2}") ? "<date>" : v));
        return copy;
    }

    /** Replaces every UUID inside a text by its label, or by <uuid> when it is not a fixture id. */
    private String labelText(String text) {
        Matcher m = UUID_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = m.group().toLowerCase();
            m.appendReplacement(out, Matcher.quoteReplacement(labels.getOrDefault(found, "<uuid>")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Normalizes a response of {@code endpoint} ("METHOD /path" with {id} placeholders); see {@link #UNORDERED} for array order. */
    private JsonNode normalize(JsonNode node, String endpoint) {
        return normalize(node, "$", UNORDERED.getOrDefault(endpoint, java.util.Set.of()));
    }

    private JsonNode normalize(JsonNode node, String path, java.util.Set<String> unordered) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) sorted.set(name, normalize(node.get(name), path + "." + name, unordered));
            return sorted;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(normalize(item, path + "[]", unordered)));
            if (unordered.contains(path)) items.sort(Comparator.comparing(JsonNode::toString)); // the API defines no order here (see UNORDERED)
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            items.forEach(array::add);
            return array;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (DATE_TIME.matcher(text).matches()) return TextNode.valueOf("<datetime>");
            if (DATE.matcher(text).matches()) {
                long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(text));
                return TextNode.valueOf(days == 0 ? "<today>" : days > 0 ? "<today+" + days + ">" : "<today" + days + ">");
            }
            return TextNode.valueOf(labelText(text));
        }
        return node;
    }
}
