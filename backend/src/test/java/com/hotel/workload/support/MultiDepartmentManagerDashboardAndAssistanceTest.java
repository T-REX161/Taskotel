package com.hotel.workload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
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
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * P4 of the multi-department Manager change, over HTTP with real JWTs: the Manager dashboard (totals across the managed departments plus a
 * breakdown per department), workload recommendations for every managed department, and what Cross-Department Assistance shows about
 * donors (figures only for donors the Manager manages too). Expected numbers are worked out here from the fixture, not read from the code.
 *
 * <p>Fixture. HOME (primary): staff h1 (assigned to task hA), h2 (UNAVAILABLE); tasks hP1, hP2 pending, hA assigned, hC completed.
 * G1 (granted): staff g1a (assigned to g1I, in progress), g1b (free); tasks g1P pending, g1I in progress, g1X cancelled; in the
 * recommendation tests two more pending tasks. OTHER (never granted): staff o1, three pending tasks. DONOR (never granted): four free
 * staff. G2 (granted where a test says so): three free staff.
 */
@SpringBootTest
@Transactional
public class MultiDepartmentManagerDashboardAndAssistanceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;
    private ScopeFixtures sf;

    private Department home, g1, g2, other, donor, shut;
    private User managerUser;
    private String admin, mgr, mgr2, staffToken;
    private Task hP1;
    private Staff h1;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        sf = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        home = fx.department("H_HOME");
        g1 = fx.department("G_ONE");
        g2 = fx.department("G_TWO");
        other = fx.department("O_OTHER");
        donor = fx.department("A_DONOR");
        shut = fx.department("S_SHUT");

        sf.account("p4_admin", Role.ADMIN, true);
        managerUser = sf.manager("p4_mgr", home).getUser();
        sf.manager("p4_mgr2", other);
        admin = bearer("p4_admin", Role.ADMIN);
        mgr = bearer("p4_mgr", Role.MANAGER);
        mgr2 = bearer("p4_mgr2", Role.MANAGER);

        // HOME
        h1 = fx.eligible("H One", home);
        fx.person("H Two", home, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        hP1 = fx.waiting("hP1", home, Priority.MEDIUM, 30);
        fx.waiting("hP2", home, Priority.MEDIUM, 30);
        fx.assign(fx.task("hA", home, Priority.MEDIUM, 30, TaskStatus.PENDING), h1);
        fx.task("hC", home, Priority.MEDIUM, 30, TaskStatus.COMPLETED);
        // G1
        Staff g1a = fx.eligible("G One A", g1);
        fx.eligible("G One B", g1);
        fx.waiting("g1P", g1, Priority.MEDIUM, 30);
        Task g1I = fx.task("g1I", g1, Priority.MEDIUM, 30, TaskStatus.PENDING);
        fx.assign(g1I, g1a);
        g1I.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(g1I);
        fx.task("g1X", g1, Priority.MEDIUM, 30, TaskStatus.CANCELLED);
        // OTHER
        fx.eligible("O One", other);
        for (int i = 1; i <= 3; i++) fx.waiting("oP" + i, other, Priority.MEDIUM, 30);
        // DONOR and G2: free staff with nothing waiting
        for (int i = 1; i <= 4; i++) fx.eligible("Donor " + i, donor);
        for (int i = 1; i <= 3; i++) fx.eligible("Gtwo " + i, g2);
        staffToken = bearer(h1.getUser().getUsername(), Role.STAFF);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String bearer(String username, Role role) {
        return "Bearer " + jwt.generateToken(username, role);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : mapper.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private JsonNode get(String url, String token) throws Exception {
        MvcResult result = call(HttpMethod.GET, url, token, null);
        assertEquals(200, result.getResponse().getStatus(), url + ": " + result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode post(String url, String token, Object body) throws Exception {
        MvcResult result = call(HttpMethod.POST, url, token, body);
        assertEquals(200, result.getResponse().getStatus(), url + ": " + result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private int status(HttpMethod method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    /** The Admin replaces the Manager's additional departments. */
    private void grant(Department... departments) throws Exception {
        List<String> codes = new ArrayList<>();
        for (Department d : departments) codes.add(d.getCode());
        MvcResult result = call(HttpMethod.PUT, "/api/users/" + managerUser.getId() + "/view-departments", admin, Map.of("departments", codes));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** The Admin switches Cross-Department Assistance off hotel-wide (the other settings are kept as they are). */
    private void switchAssistanceOff() throws Exception {
        JsonNode current = get("/api/system-settings", admin);
        Map<String, Object> body = Map.of("autoBalancingEnabled", current.get("autoBalancingEnabled").asBoolean(),
                "capacityAlertEnabled", current.get("capacityAlertEnabled").asBoolean(),
                "crossDepartmentAssistanceEnabled", false,
                "capacityThresholdPercent", current.get("capacityThresholdPercent").asInt());
        assertEquals(200, status(HttpMethod.PUT, "/api/system-settings", admin, body));
    }

    private JsonNode dashboard(String token) throws Exception {
        return get("/api/dashboard/summary", token);
    }

    private static List<String> codesOf(JsonNode array, String field) {
        List<String> codes = new ArrayList<>();
        for (JsonNode node : array) codes.add(node.get(field).asText());
        return codes;
    }

    private static JsonNode entry(JsonNode breakdown, Department department) {
        for (JsonNode node : breakdown) if (node.get("departmentCode").asText().equals(department.getCode())) return node;
        throw new AssertionError("no breakdown entry for " + department.getCode());
    }

    private static final List<String> FIGURES = List.of("totalStaff", "availableStaff", "unavailableStaff", "inactiveStaff", "totalTasks", "pendingTasks",
            "assignedTasks", "inProgressTasks", "completedTasks", "cancelledTasks", "totalActiveAssignments", "totalStaffWithWorkload",
            "lowWorkloadStaff", "normalWorkloadStaff", "highWorkloadStaff", "atRiskStaff");

    private static void assertTotalsAreTheSumOfTheBreakdown(JsonNode dashboard) {
        for (String figure : FIGURES) {
            long sum = 0;
            for (JsonNode department : dashboard.get("departmentBreakdown")) sum += department.get(figure).asLong();
            assertEquals(sum, dashboard.get(figure).asLong(), "the total of " + figure + " is the sum over the departments");
        }
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dashboard: totals across the primary and an additional department, with a breakdown per department (primary first, flagged)")
    void dashboardAggregateAndBreakdown() throws Exception {
        grant(g1);
        JsonNode d = dashboard(mgr);

        assertEquals(List.of(home.getCode(), g1.getCode()), codesOf(d.get("departmentBreakdown"), "departmentCode"), "primary first, then the additional ones");
        assertTrue(entry(d.get("departmentBreakdown"), home).get("primary").asBoolean());
        assertFalse(entry(d.get("departmentBreakdown"), g1).get("primary").asBoolean());
        assertEquals(home.getName(), entry(d.get("departmentBreakdown"), home).get("departmentName").asText());

        JsonNode h = entry(d.get("departmentBreakdown"), home);
        assertEquals(2, h.get("totalStaff").asLong());
        assertEquals(1, h.get("availableStaff").asLong());
        assertEquals(1, h.get("unavailableStaff").asLong());
        assertEquals(0, h.get("inactiveStaff").asLong());
        assertEquals(4, h.get("totalTasks").asLong());
        assertEquals(2, h.get("pendingTasks").asLong());
        assertEquals(1, h.get("assignedTasks").asLong());
        assertEquals(0, h.get("inProgressTasks").asLong());
        assertEquals(1, h.get("completedTasks").asLong());
        assertEquals(0, h.get("cancelledTasks").asLong());
        assertEquals(1, h.get("totalActiveAssignments").asLong());
        assertEquals(1, h.get("totalStaffWithWorkload").asLong());

        JsonNode g = entry(d.get("departmentBreakdown"), g1);
        assertEquals(2, g.get("totalStaff").asLong());
        assertEquals(2, g.get("availableStaff").asLong());
        assertEquals(0, g.get("unavailableStaff").asLong());
        assertEquals(3, g.get("totalTasks").asLong());
        assertEquals(1, g.get("pendingTasks").asLong());
        assertEquals(1, g.get("inProgressTasks").asLong());
        assertEquals(1, g.get("cancelledTasks").asLong());
        assertEquals(1, g.get("totalActiveAssignments").asLong());
        assertEquals(1, g.get("totalStaffWithWorkload").asLong());

        // The totals: worked out here (4 staff, 7 tasks, ...), and equal to the sum of the breakdown
        assertEquals(4, d.get("totalStaff").asLong());
        assertEquals(3, d.get("availableStaff").asLong());
        assertEquals(1, d.get("unavailableStaff").asLong());
        assertEquals(7, d.get("totalTasks").asLong());
        assertEquals(3, d.get("pendingTasks").asLong());
        assertEquals(1, d.get("assignedTasks").asLong());
        assertEquals(1, d.get("inProgressTasks").asLong());
        assertEquals(1, d.get("completedTasks").asLong());
        assertEquals(1, d.get("cancelledTasks").asLong());
        assertEquals(2, d.get("totalActiveAssignments").asLong());
        assertEquals(2, d.get("totalStaffWithWorkload").asLong());
        long buckets = d.get("lowWorkloadStaff").asLong() + d.get("normalWorkloadStaff").asLong() + d.get("highWorkloadStaff").asLong() + d.get("atRiskStaff").asLong();
        assertEquals(4, buckets, "every staff member of the managed departments is in exactly one workload bucket");
        assertTotalsAreTheSumOfTheBreakdown(d);
    }

    @Test
    @DisplayName("Dashboard: a Manager with only a primary department gets one breakdown entry equal to the totals")
    void dashboardPrimaryOnly() throws Exception {
        JsonNode d = dashboard(mgr);
        assertEquals(List.of(home.getCode()), codesOf(d.get("departmentBreakdown"), "departmentCode"));
        assertTrue(d.get("departmentBreakdown").get(0).get("primary").asBoolean());
        assertEquals(4, d.get("totalTasks").asLong());
        assertEquals(2, d.get("totalStaff").asLong());
        assertTotalsAreTheSumOfTheBreakdown(d);
    }

    @Test
    @DisplayName("Dashboard: several additional departments are all covered; each Manager sees only their own departments")
    void dashboardMultipleGrantsAndOtherManagers() throws Exception {
        grant(g1, g2);
        JsonNode d = dashboard(mgr);
        assertEquals(List.of(home.getCode(), g1.getCode(), g2.getCode()), codesOf(d.get("departmentBreakdown"), "departmentCode"),
                "the primary department first, then the additional ones by name");
        assertEquals(3, entry(d.get("departmentBreakdown"), g2).get("totalStaff").asLong());
        assertEquals(0, entry(d.get("departmentBreakdown"), g2).get("totalTasks").asLong());
        assertEquals(7, d.get("totalTasks").asLong());
        assertEquals(7, d.get("totalStaff").asLong());
        assertTotalsAreTheSumOfTheBreakdown(d);

        JsonNode theirs = dashboard(mgr2);
        assertEquals(List.of(other.getCode()), codesOf(theirs.get("departmentBreakdown"), "departmentCode"), "another Manager: their own department only");
        assertEquals(3, theirs.get("totalTasks").asLong());
        assertEquals(1, theirs.get("totalStaff").asLong());
        assertFalse(theirs.toString().contains(home.getCode()) || theirs.toString().contains(g1.getCode()), "nothing of another Manager's departments");
        assertFalse(dashboard(mgr).get("departmentBreakdown").toString().contains(other.getCode()));
    }

    @Test
    @DisplayName("Dashboard: an inactive additional department is left out of the totals and the breakdown; revocation applies on the next request")
    void dashboardInactiveAndRevoked() throws Exception {
        fx.waiting("sP1", shut, Priority.MEDIUM, 30);
        fx.eligible("S One", shut);
        grant(g1, shut);
        JsonNode before = dashboard(mgr);
        assertEquals(3, before.get("departmentBreakdown").size());
        assertEquals(8, before.get("totalTasks").asLong());

        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        JsonNode inactive = dashboard(mgr);
        assertEquals(List.of(home.getCode(), g1.getCode()), codesOf(inactive.get("departmentBreakdown"), "departmentCode"));
        assertEquals(7, inactive.get("totalTasks").asLong());
        assertEquals(4, inactive.get("totalStaff").asLong());

        grant(); // revoked
        JsonNode revoked = dashboard(mgr);
        assertEquals(List.of(home.getCode()), codesOf(revoked.get("departmentBreakdown"), "departmentCode"));
        assertEquals(4, revoked.get("totalTasks").asLong());
    }

    @Test
    @DisplayName("Dashboard: an inactive primary department refuses the Manager everything, even with active additional departments")
    void dashboardInactivePrimaryIsRefused() throws Exception {
        grant(g1);
        assertEquals(200, status(HttpMethod.GET, "/api/dashboard/summary", mgr, null));
        home.setActive(false);
        departmentRepository.saveAndFlush(home);
        assertEquals(403, status(HttpMethod.GET, "/api/dashboard/summary", mgr, null));
        assertEquals(403, status(HttpMethod.GET, "/api/workload/recommendations", mgr, null));
        assertEquals(403, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + g1.getCode(), mgr, null));
    }

    @Test
    @DisplayName("Dashboard: Admin and Staff answers carry no breakdown, whatever grants exist")
    void dashboardAdminAndStaffUnchanged() throws Exception {
        grant(g1, g2);
        JsonNode adminDashboard = dashboard(admin);
        assertFalse(adminDashboard.has("departmentBreakdown"), "an Admin's answer has no breakdown");
        assertEquals(taskRepository.count(), adminDashboard.get("totalTasks").asLong(), "the Admin's totals still cover every task in every department");
        assertFalse(dashboard(staffToken).has("departmentBreakdown"), "a Staff member's answer has no breakdown");
    }

    // ── Recommendations ──────────────────────────────────────────────────────

    /** Two more pending tasks in G1, so that G1 and HOME both have waiting work and no free staff to cover it. */
    private void pressureG1() {
        fx.waiting("g1P2", g1, Priority.MEDIUM, 30);
        fx.waiting("g1P3", g1, Priority.MEDIUM, 30);
    }

    @Test
    @DisplayName("Recommendations: generated for every managed department, and for no other department even when it is under pressure")
    void recommendationsForEveryManagedDepartment() throws Exception {
        pressureG1();
        grant(g1, g2);
        JsonNode recommendations = get("/api/workload/recommendations", mgr).get("recommendations");
        Set<String> receivers = new HashSet<>(codesOf(recommendations, "departmentCode"));
        assertEquals(Set.of(home.getCode(), g1.getCode()), receivers, "the primary department and the additional one; not OTHER, which is under pressure too");
        // Each department's own figures. (A department's Manager is one of its Staff records and counts as one free person, as it always has:
        // HOME has 2 waiting tasks and 1 free person; G1 has 3 waiting tasks and 1 free person.)
        for (JsonNode recommendation : recommendations) {
            int expectedUncovered = recommendation.get("departmentCode").asText().equals(home.getCode()) ? 1 : 2;
            assertEquals(expectedUncovered, recommendation.get("pressure").get("uncoveredTasks").asInt(), recommendation.get("departmentName").asText());
        }

        Set<String> adminReceivers = new HashSet<>(codesOf(get("/api/workload/recommendations", admin).get("recommendations"), "departmentCode"));
        assertTrue(adminReceivers.containsAll(Set.of(home.getCode(), g1.getCode(), other.getCode())), "an Admin still gets every department");

        // A Manager without grants still gets only their primary department
        grant();
        assertEquals(List.of(home.getCode()), codesOf(get("/api/workload/recommendations", mgr).get("recommendations"), "departmentCode"));
    }

    @Test
    @DisplayName("Recommendations: a person is suggested once; a donor in a managed department is shown in full, every other donor stays redacted")
    void recommendationsRedactDonorsPerDepartment() throws Exception {
        pressureG1();
        grant(g1, g2);
        JsonNode recommendations = get("/api/workload/recommendations", mgr).get("recommendations");
        Set<String> names = new HashSet<>();
        int managedDonorSuggestions = 0;
        int otherDonorSuggestions = 0;
        for (JsonNode recommendation : recommendations) {
            for (JsonNode suggestion : recommendation.get("suggestions")) {
                assertTrue(names.add(suggestion.get("staffName").asText()), "nobody is suggested to two departments: " + suggestion.get("staffName"));
                String homeName = suggestion.get("homeDepartmentName").asText();
                if (homeName.equals(g2.getName())) {
                    managedDonorSuggestions++;
                    assertTrue(suggestion.hasNonNull("staffId"), "managed donor: the person's id is shown");
                    assertEquals(g2.getCode(), suggestion.get("homeDepartmentCode").asText());
                    assertTrue(suggestion.hasNonNull("donorImpact"), "managed donor: the donor's before and after figures are shown");
                    assertTrue(suggestion.get("donorImpact").get("freeStaffBefore").asInt() > suggestion.get("donorImpact").get("freeStaffAfter").asInt());
                } else {
                    otherDonorSuggestions++;
                    assertEquals(donor.getName(), homeName);
                    assertTrue(suggestion.get("staffId").isNull(), "any other donor: no person id");
                    assertTrue(suggestion.get("homeDepartmentCode").isNull(), "any other donor: no department code");
                    assertTrue(suggestion.get("donorImpact").isNull(), "any other donor: no donor figures");
                    assertEquals(2, suggestion.get("reasons").size(), "only the neutral reasons");
                }
            }
        }
        assertEquals(3, managedDonorSuggestions + otherDonorSuggestions, "one suggestion for HOME (1 uncovered task) and two for G1 (2 uncovered)");
        assertTrue(managedDonorSuggestions > 0 && otherDonorSuggestions > 0, "both kinds of donor are used in this fixture");
    }

    @Test
    @DisplayName("Recommendations: an inactive additional department gets none (and lends no one); an inactive switch or primary refuses as before")
    void recommendationsInactiveAndSwitch() throws Exception {
        for (int i = 1; i <= 3; i++) fx.waiting("sP" + i, shut, Priority.MEDIUM, 30);
        fx.eligible("S One", shut);
        grant(shut);
        assertTrue(codesOf(get("/api/workload/recommendations", mgr).get("recommendations"), "departmentCode").contains(shut.getCode()), "active: a receiver");

        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        JsonNode after = get("/api/workload/recommendations", mgr);
        assertFalse(after.toString().contains(shut.getCode()) || after.toString().contains(shut.getName()), "inactive: nothing about it");

        // The hotel-wide switch stays effective: switched off, a Manager gets no recommendations at all
        switchAssistanceOff();
        JsonNode off = get("/api/workload/recommendations", mgr);
        assertEquals(0, off.get("recommendations").size());
        assertEquals(0, off.get("unsupported").size());
    }

    // ── Cross-Department Assistance: donor figures ───────────────────────────

    private static JsonNode donorEntry(JsonNode donors, Department department) {
        for (JsonNode node : donors) if (node.get("code").asText().equals(department.getCode())) return node;
        throw new AssertionError("no donor " + department.getCode());
    }

    @Test
    @DisplayName("Assistance options: donor figures for a donor the Manager manages (equal to what an Admin sees), none for any other donor")
    void optionsShowFiguresForManagedDonorsOnly() throws Exception {
        grant(g2);
        JsonNode managerOptions = get("/api/support/options?receivingDepartment=" + home.getCode(), mgr);
        JsonNode adminOptions = get("/api/support/options?receivingDepartment=" + home.getCode(), admin);

        JsonNode managed = donorEntry(managerOptions.get("donors"), g2);
        JsonNode adminSame = donorEntry(adminOptions.get("donors"), g2);
        for (String figure : List.of("freeStaff", "availableStaff", "waitingTasks", "maxLendable", "overrideLendable", "canLend", "note")) {
            assertEquals(adminSame.get(figure), managed.get(figure), "the managed donor's " + figure + " is what an Admin sees");
        }
        assertEquals(3, managed.get("freeStaff").asInt());
        assertEquals(2, managed.get("maxLendable").asInt(), "three free, one stays in reserve");
        assertEquals(3, managed.get("staff").size());
        for (JsonNode person : managed.get("staff")) {
            assertEquals(0, person.get("activeTaskCount").asInt(), "lendable people are free, so they hold no work");
            assertEquals(0, person.get("totalEstimatedDuration").asInt());
            assertTrue(person.hasNonNull("workloadStatus"));
        }
        assertEquals(adminSame.get("staff").toString(), managed.get("staff").toString(), "the person list matches an Admin's");

        JsonNode unauthorized = donorEntry(managerOptions.get("donors"), donor);
        for (String figure : List.of("freeStaff", "availableStaff", "waitingTasks", "maxLendable", "overrideLendable", "canLend", "note")) {
            assertTrue(unauthorized.get(figure).isNull(), "an unauthorized donor's " + figure + " stays hidden");
        }
        for (JsonNode person : unauthorized.get("staff")) {
            assertTrue(person.get("activeTaskCount").isNull() && person.get("totalEstimatedDuration").isNull() && person.get("workloadStatus").isNull());
        }
        assertTrue(donorEntry(adminOptions.get("donors"), donor).get("freeStaff").asInt() > 0, "an Admin sees it, so the hiding is the Manager's rule");
    }

    @Test
    @DisplayName("Assistance options: a department the Manager does not manage is not found as a receiver, with or without grants; Staff are refused")
    void optionsForUnmanagedDepartmentsAreNotFound() throws Exception {
        grant(g2);
        assertEquals(200, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + g2.getCode(), mgr, null));
        assertEquals(404, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + other.getCode(), mgr, null));
        assertEquals(404, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + donor.getCode(), mgr, null));
        assertEquals(404, status(HttpMethod.GET, "/api/support/options?receivingDepartment=NO_SUCH_DEPARTMENT", mgr, null));
        assertEquals(403, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + home.getCode(), staffToken, null));
        // Revoked: the very next request
        grant();
        assertEquals(404, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + g2.getCode(), mgr, null));
        // The donor figures go with the grant
        assertTrue(donorEntry(get("/api/support/options?receivingDepartment=" + home.getCode(), mgr).get("donors"), g2).get("freeStaff").isNull());
    }

    @Test
    @DisplayName("Assistance preview: donor impact only for a managed donor, equal to an Admin's; an unmanaged donor's impact is never shown")
    void previewShowsImpactForManagedDonorsOnly() throws Exception {
        grant(g2);
        Staff fromManaged = staffRepository.findByDepartmentAndUserRole(g2, Role.STAFF).get(0);
        Staff fromOther = staffRepository.findByDepartmentAndUserRole(donor, Role.STAFF).get(0);

        Map<String, Object> managedPlan = Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", fromManaged.getId().toString(), "taskId", hP1.getId().toString())));
        JsonNode managerPreview = post("/api/support/preview", mgr, managedPlan);
        JsonNode adminPreview = post("/api/support/preview", admin, managedPlan);
        assertTrue(managerPreview.get("valid").asBoolean());
        assertEquals(1, managerPreview.get("donors").size());
        assertEquals(adminPreview.get("donors").get(0), managerPreview.get("donors").get(0), "the managed donor's before and after figures equal an Admin's");
        assertEquals(g2.getCode(), managerPreview.get("donors").get(0).get("code").asText());
        assertEquals(adminPreview.get("fingerprint"), managerPreview.get("fingerprint"), "the stale-plan fingerprint is the same for both");

        Map<String, Object> otherPlan = Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", fromOther.getId().toString(), "taskId", hP1.getId().toString())));
        JsonNode otherManager = post("/api/support/preview", mgr, otherPlan);
        assertTrue(otherManager.get("valid").asBoolean());
        assertEquals(0, otherManager.get("donors").size(), "no figures for a donor the Manager does not manage");
        assertEquals(1, post("/api/support/preview", admin, otherPlan).get("donors").size(), "an Admin still sees it");
        assertEquals(adminPreview.get("receiving"), managerPreview.get("receiving"), "the receiving department's impact is unchanged");
    }

    @Test
    @DisplayName("Assistance preview: a reserve problem is explained in full only for a managed donor; an unmanaged donor's stays generic")
    void previewReserveProblemsFollowTheManagedSet() throws Exception {
        Department tight = fx.department("T_TIGHT");
        Department tightOther = fx.department("T_TIGHTO");
        Staff onlyManaged = fx.eligible("Tight Managed", tight);
        Staff onlyOther = fx.eligible("Tight Other", tightOther);
        grant(tight);

        JsonNode managed = post("/api/support/preview", mgr, Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", onlyManaged.getId().toString(), "taskId", hP1.getId().toString()))));
        assertFalse(managed.get("valid").asBoolean());
        JsonNode problem = managed.get("problems").get(0);
        assertEquals("DONOR_RESERVE", problem.get("code").asText());
        assertTrue(problem.get("message").asText().startsWith(tight.getName() + " would be left with 0 free staff"), problem.get("message").asText());
        assertTrue(managed.get("canOverrideReserve").asBoolean(), "the override hint is shown for a managed donor");

        JsonNode unmanaged = post("/api/support/preview", mgr, Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", onlyOther.getId().toString(), "taskId", hP1.getId().toString()))));
        assertFalse(unmanaged.get("valid").asBoolean());
        assertEquals("The lending department cannot spare this person right now.", unmanaged.get("problems").get(0).get("message").asText());
        assertFalse(unmanaged.get("canOverrideReserve").asBoolean(), "no hint for an unmanaged donor");
        assertFalse(unmanaged.get("problems").toString().contains(tightOther.getName()), "the explanation does not name the unmanaged donor or give its figures");
        assertFalse(unmanaged.get("problems").toString().contains("free staff"));

        // An Admin still gets the full explanation for the unmanaged donor
        JsonNode adminView = post("/api/support/preview", admin, Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", onlyOther.getId().toString(), "taskId", hP1.getId().toString()))));
        assertTrue(adminView.get("problems").get(0).get("message").asText().startsWith(tightOther.getName() + " would be left with"));
    }

    @Test
    @DisplayName("Assistance preview: an acknowledged reserve override is explained in full only for a managed donor")
    void previewOverrideWarningsFollowTheManagedSet() throws Exception {
        Department tight = fx.department("T_TIGHT");
        Department tightOther = fx.department("T_TIGHTO");
        Staff onlyManaged = fx.eligible("Tight Managed", tight);
        Staff onlyOther = fx.eligible("Tight Other", tightOther);
        grant(tight);

        Map<String, Object> managedPlan = Map.of("receivingDepartment", home.getCode(), "acknowledgeReserveOverride", true,
                "items", List.of(Map.of("staffId", onlyManaged.getId().toString(), "taskId", hP1.getId().toString())));
        JsonNode managed = post("/api/support/preview", mgr, managedPlan);
        assertTrue(managed.get("valid").asBoolean(), "the acknowledgement allows the one-person reserve to go");
        assertTrue(managed.get("warnings").get(0).get("message").asText().startsWith(tight.getName() + " will be left with 0 free staff"),
                managed.get("warnings").get(0).get("message").asText());
        assertEquals(1, managed.get("donors").size());

        Map<String, Object> otherPlan = Map.of("receivingDepartment", home.getCode(), "acknowledgeReserveOverride", true,
                "items", List.of(Map.of("staffId", onlyOther.getId().toString(), "taskId", hP1.getId().toString())));
        JsonNode unmanaged = post("/api/support/preview", mgr, otherPlan);
        assertTrue(unmanaged.get("valid").asBoolean());
        assertEquals("The lending department's staffing is tight.", unmanaged.get("warnings").get(0).get("message").asText());
        assertEquals(0, unmanaged.get("donors").size());
        assertTrue(post("/api/support/preview", admin, otherPlan).get("warnings").get(0).get("message").asText()
                .startsWith(tightOther.getName() + " will be left with 0 free staff"), "an Admin still gets the full warning");
    }

    @Test
    @DisplayName("Assistance: confirming stays Admin-only, planning follows the hotel-wide switch, and nothing is stored by a Manager's planning")
    void confirmationAndSwitchAreUnchanged() throws Exception {
        grant(g2);
        Staff fromManaged = staffRepository.findByDepartmentAndUserRole(g2, Role.STAFF).get(0);
        Map<String, Object> plan = Map.of("receivingDepartment", home.getCode(),
                "items", List.of(Map.of("staffId", fromManaged.getId().toString(), "taskId", hP1.getId().toString())));
        long recordsBefore = supportRepository.count();
        assertEquals(403, status(HttpMethod.POST, "/api/support/assignments", mgr, plan), "a Manager can never confirm, even for departments they manage");
        assertEquals(403, status(HttpMethod.POST, "/api/support/assignments", staffToken, plan));
        post("/api/support/preview", mgr, plan);
        assertEquals(recordsBefore, supportRepository.count(), "planning stores nothing");

        switchAssistanceOff();
        assertTrue(status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + home.getCode(), mgr, null) >= 400, "planning is off for everyone");
        assertTrue(status(HttpMethod.POST, "/api/support/preview", mgr, plan) >= 400);
        assertNotNull(get("/api/support/assignments", mgr), "existing arrangements stay visible");
    }
}
