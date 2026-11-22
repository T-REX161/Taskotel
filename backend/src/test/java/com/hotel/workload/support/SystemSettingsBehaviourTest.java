package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.assignment.AssignmentService;
import com.hotel.workload.assignment.CreateAssignmentRequest;
import com.hotel.workload.dashboard.AdminDashboardSummaryDto;
import com.hotel.workload.dashboard.DashboardService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadRecommendationService;
import com.hotel.workload.workload.WorkloadRecommendationsDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four System Settings now change behaviour (Stage 4D): Cross-Department Assistance blocks NEW support plans (preview and confirm, never
 * existing support), the capacity alert and threshold decide the dashboard's capacity warnings, and Auto-Balancing switches the
 * recommendations (suggestions only: nothing is ever reassigned). Real services and H2; every test rolls back, including the settings row.
 */
@SpringBootTest
@Transactional
public class SystemSettingsBehaviourTest {

    @Autowired private SupportAssignmentService support;
    @Autowired private WorkloadRecommendationService recommendations;
    @Autowired private DashboardService dashboard;
    @Autowired private AssignmentService assignmentService;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        admin = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).account("s4d_admin", Role.ADMIN, true);
        SupportTestFixture.signInAs("s4d_admin", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Writes the one settings row (inside this test's transaction, so it is rolled back). */
    private com.hotel.workload.security.User admin;

    private void settings(boolean autoBalancing, boolean capacityAlert, boolean crossDepartment, int threshold) {
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(autoBalancing);
        row.setCapacityAlertEnabled(capacityAlert);
        row.setCrossDepartmentAssistanceEnabled(crossDepartment);
        row.setCapacityThresholdPercent(threshold);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        row.setUpdatedByUserId(admin.getId());
        row.setUpdatedByUsername(admin.getUsername());
        settingsRepository.saveAndFlush(row);
    }

    private static boolean has(SupportPlanException e, String code) {
        return e.getProblems().stream().anyMatch(p -> code.equals(p.code()));
    }

    // ── Cross-Department Assistance ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OFF: a new cross-department plan cannot be previewed (409), and nothing is written")
    void previewBlockedWhenOff() {
        var s = fx.overloaded(2, 2);
        settings(true, true, false, 85);
        long supportBefore = supportRepository.count();
        SupportPlanException e = assertThrows(SupportPlanException.class, () -> support.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));
        assertEquals(409, e.getStatus().value());
        assertTrue(has(e, "CROSS_DEPARTMENT_ASSISTANCE_DISABLED"));
        assertEquals(supportBefore, supportRepository.count());
    }

    @Test
    @DisplayName("OFF: a new cross-department plan cannot be confirmed (409): no support record and no assignment is created")
    void confirmBlockedWhenOff() {
        var s = fx.overloaded(2, 2);
        settings(true, true, false, 85);
        long supportBefore = supportRepository.count();
        long assignmentsBefore = assignmentRepository.count();
        SupportPlanException e = assertThrows(SupportPlanException.class, () -> support.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))));
        assertEquals(409, e.getStatus().value());
        assertTrue(has(e, "CROSS_DEPARTMENT_ASSISTANCE_DISABLED"));
        assertEquals(supportBefore, supportRepository.count());
        assertEquals(assignmentsBefore, assignmentRepository.count());
        assertTrue(assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).isEmpty(), "the waiting task stays unassigned");
    }

    @Test
    @DisplayName("ON (and the default, with no row saved): previewing and confirming work as before")
    void onKeepsExistingBehaviour() {
        var s = fx.overloaded(2, 2);
        settings(true, true, true, 85);
        assertTrue(support.preview(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).valid());
        assertEquals(1, support.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).size());
    }

    @Test
    @DisplayName("Existing support stays valid and visible when the setting is turned OFF, and can still be ended")
    void existingSupportUnaffectedWhenOff() {
        var s = fx.overloaded(2, 2);
        settings(true, true, true, 85);
        SupportAssignmentDto created = support.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).get(0);
        long assignmentsBefore = assignmentRepository.count();

        settings(true, true, false, 85);

        SupportAssignment stored = supportRepository.findById(created.id()).orElseThrow();
        assertEquals(SupportStatus.ACTIVE, stored.getStatus(), "not cancelled or ended by the setting");
        assertEquals(assignmentsBefore, assignmentRepository.count(), "no assignment removed");
        assertTrue(support.list(SupportStatus.ACTIVE).stream().anyMatch(r -> r.id().equals(created.id())), "still listed");
        // Ending an existing support by hand is not creating a new arrangement, so it still works
        assertNotNull(support.end(created.id()));
    }

    @Test
    @DisplayName("Same-department work still works with the setting OFF")
    void sameDepartmentOperationsStillWorkWhenOff() {
        settings(true, true, false, 85);
        Department department = fx.department("S");
        Staff member = fx.eligible("Same Dept Sam", department);
        Task task = fx.waiting("Same department job", department, Priority.MEDIUM, 30);
        assertNotNull(assignmentService.createAssignment(new CreateAssignmentRequest(task.getId(), member.getId())));
        assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
    }

    // ── Capacity alert and threshold: utilization = remaining task minutes / (eligible staff x 480 min) x 100 ────────────

    /**
     * A department with {@code staff} eligible people (capacity staff x 480 minutes). Each {@code assigned} value is a task of that many
     * minutes held by a different person; each {@code waiting} value is a task nobody holds. Remaining workload = their sum.
     */
    private Department departmentLoad(String label, int staff, int[] assigned, int[] waiting) {
        Department department = fx.department(label);
        List<Staff> people = new java.util.ArrayList<>();
        for (int i = 0; i < staff; i++) people.add(fx.eligible("P" + label + i, department));
        for (int i = 0; i < assigned.length; i++) fx.assign(fx.task("Held " + label + i, department, Priority.MEDIUM, assigned[i], TaskStatus.PENDING), people.get(i));
        for (int i = 0; i < waiting.length; i++) fx.waiting("Wait " + label + i, department, Priority.MEDIUM, waiting[i]);
        return department;
    }

    private static final int[] NONE = new int[0];

    private List<AdminDashboardSummaryDto.CapacityAlert> alertsFor(Department department) {
        AdminDashboardSummaryDto summary = (AdminDashboardSummaryDto) dashboard.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        return summary.getCapacityAlerts().stream().filter(a -> a.departmentCode().equals(department.getCode())).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Capacity alert ON: a department at or above the threshold is reported once, with its minutes, capacity and utilization")
    void alertShownWhenEnabledAndThresholdReached() {
        Department department = departmentLoad("A", 2, new int[]{240, 240}, NONE);          // 480 min of work, 2 people = 960 min of capacity = 50%
        settings(true, true, true, 50);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size(), "one warning per department");
        AdminDashboardSummaryDto.CapacityAlert alert = alerts.get(0);
        assertEquals(480, alert.remainingMinutes());
        assertEquals(960, alert.capacityMinutes());
        assertEquals(2, alert.eligibleStaff());
        assertEquals(50.0, alert.utilizationPercent());
        assertEquals(50, alert.thresholdPercent());
        assertFalse(alert.noCapacity());
    }

    @Test
    @DisplayName("Capacity alert OFF: no warnings at all, whatever the load")
    void alertSuppressedWhenDisabled() {
        Department department = departmentLoad("B", 1, new int[]{480}, new int[]{480});     // 200% of capacity
        settings(true, false, true, 1);
        assertTrue(alertsFor(department).isEmpty());
        AdminDashboardSummaryDto summary = (AdminDashboardSummaryDto) dashboard.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(summary.getCapacityAlerts().isEmpty());
    }

    @Test
    @DisplayName("The threshold is the real condition, compared exactly: 50% warns at 50 but not at 51, and 959 of 960 minutes is not 100%")
    void thresholdIsExactAtTheBoundary() {
        Department half = departmentLoad("H", 1, new int[]{240}, NONE);                      // 240 / 480 = exactly 50%
        Department nearlyFull = departmentLoad("F", 2, new int[]{480, 479}, NONE);           // 959 / 960 = 99.9%, not 100
        Department full = departmentLoad("L", 2, new int[]{480, 480}, NONE);                 // 960 / 960 = exactly 100%
        Department tiny = departmentLoad("X", 1, new int[]{5}, NONE);                        // 5 / 480 = 1.04%
        for (int threshold : new int[]{1, 50}) {
            settings(true, true, true, threshold);
            assertEquals(1, alertsFor(half).size(), "50% reaches a threshold of " + threshold);
        }
        settings(true, true, true, 51);
        assertTrue(alertsFor(half).isEmpty(), "50% does not reach 51");
        settings(true, true, true, 100);
        assertEquals(1, alertsFor(full).size(), "exactly 100% reaches 100");
        assertTrue(alertsFor(nearlyFull).isEmpty(), "959 of 960 minutes is below 100%, however it rounds for display");
        settings(true, true, true, 1);
        assertEquals(1, alertsFor(tiny).size(), "a tiny load is at risk at a tiny threshold");
        settings(true, true, true, 2);
        assertTrue(alertsFor(tiny).isEmpty(), "and not at 2");
    }

    @Test
    @DisplayName("Waiting work counts as workload: 2 people and two waiting 480-minute tasks are at 100%")
    void waitingTasksCountAsWorkload() {
        Department department = departmentLoad("W", 2, NONE, new int[]{480, 480});
        settings(true, true, true, 100);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size());
        assertEquals(960, alerts.get(0).remainingMinutes());
        assertEquals(100.0, alerts.get(0).utilizationPercent());
    }

    @Test
    @DisplayName("Only PENDING, ASSIGNED and IN_PROGRESS tasks are workload; COMPLETED and CANCELLED are not")
    void onlyActiveTasksAreWorkload() {
        Department department = departmentLoad("T", 1, NONE, NONE);
        fx.task("Pending", department, Priority.MEDIUM, 100, TaskStatus.PENDING);
        fx.task("In progress", department, Priority.MEDIUM, 100, TaskStatus.IN_PROGRESS);
        fx.task("Done", department, Priority.MEDIUM, 300, TaskStatus.COMPLETED);
        fx.task("Cancelled", department, Priority.MEDIUM, 300, TaskStatus.CANCELLED);
        settings(true, true, true, 1);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size());
        assertEquals(200, alerts.get(0).remainingMinutes(), "only the two active tasks count");
    }

    @Test
    @DisplayName("Only active, available STAFF-role people with a department are capacity: inactive, unavailable, Manager, Admin and department-less people are not")
    void onlyEligiblePeopleAreCapacity() {
        Department department = departmentLoad("E", 2, NONE, new int[]{480});
        fx.person("Inactive", department, Role.STAFF, true, false, true, Availability.AVAILABLE, false);
        fx.person("Away", department, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        fx.person("Manager", department, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        fx.person("Admin", department, Role.ADMIN, true, false, true, Availability.AVAILABLE, true);
        fx.person("No department", null, Role.STAFF, true, false, true, Availability.AVAILABLE, true);
        settings(true, true, true, 50);
        AdminDashboardSummaryDto.CapacityAlert alert = alertsFor(department).get(0);
        assertEquals(2, alert.eligibleStaff(), "only the two eligible people");
        assertEquals(960, alert.capacityMinutes());
        assertEquals(50.0, alert.utilizationPercent(), "480 min over 960, not over the capacity of the five people who do not count");
    }

    @Test
    @DisplayName("Work with nobody eligible to do it has no capacity: always reported (no percentage); no staff and no work is not")
    void noCapacityIsReportedAndEmptyIsNot() {
        Department noStaff = departmentLoad("N", 0, NONE, new int[]{60});
        Department allAway = fx.department("Z");
        fx.person("Away", allAway, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        fx.waiting("Orphan", allAway, Priority.MEDIUM, 30);
        Department empty = fx.department("M");
        settings(true, true, true, 100);
        for (Department department : List.of(noStaff, allAway)) {
            List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
            assertEquals(1, alerts.size());
            assertTrue(alerts.get(0).noCapacity());
            assertNull(alerts.get(0).utilizationPercent(), "a percentage would be meaningless");
            assertEquals(0, alerts.get(0).eligibleStaff());
        }
        assertTrue(alertsFor(empty).isEmpty(), "no staff and no work: nothing to warn about");
    }

    // ── Auto-Balancing (recommendations) ─────────────────────────────────────────────────────────────────────

    private Map<java.util.UUID, java.util.UUID> assignmentOwners() {
        return assignmentRepository.findAll().stream().collect(Collectors.toMap(Assignment::getId, a -> a.getStaff().getId()));
    }

    @Test
    @DisplayName("Auto-Balancing OFF: no recommendations are produced, only a note saying why")
    void recommendationsSuppressedWhenOff() {
        var s = fx.overloaded(3, 3);
        settings(false, true, true, 85);
        WorkloadRecommendationsDto result = recommendations.getRecommendations();
        assertTrue(result.recommendations().isEmpty());
        assertTrue(result.unsupported().isEmpty());
        assertEquals(1, result.limitations().size());
        assertTrue(result.limitations().get(0).contains("turned off in System Settings"));
    }

    @Test
    @DisplayName("Auto-Balancing ON: the existing recommendation logic is unchanged")
    void recommendationsAvailableWhenOn() {
        var s = fx.overloaded(3, 3);
        settings(true, true, true, 85);
        WorkloadRecommendationsDto result = recommendations.getRecommendations();
        assertTrue(result.recommendations().stream().anyMatch(r -> r.departmentCode().equals(s.receiving.getCode()) && !r.suggestions().isEmpty()));
        assertFalse(result.limitations().stream().anyMatch(l -> l.contains("turned off in System Settings")));
    }

    @Test
    @DisplayName("No task is ever reassigned automatically: assignments and tasks are identical after reading recommendations, ON or OFF")
    void nothingIsReassigned() {
        var s = fx.overloaded(3, 3);
        Map<java.util.UUID, java.util.UUID> before = assignmentOwners();
        List<TaskStatus> statusesBefore = taskRepository.findAll().stream().map(Task::getStatus).toList();
        long supportBefore = supportRepository.count();

        settings(true, true, true, 85);
        recommendations.getRecommendations();
        settings(false, true, true, 85);
        recommendations.getRecommendations();

        assertEquals(before, assignmentOwners());
        assertEquals(statusesBefore, taskRepository.findAll().stream().map(Task::getStatus).toList());
        assertEquals(supportBefore, supportRepository.count());
        assertTrue(s.tasks.stream().allMatch(t -> assignmentRepository.findAllByTaskId(t.getId()).isEmpty()), "waiting tasks stay unassigned");
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────────────────────────────────

    @Autowired private org.springframework.web.context.WebApplicationContext context;

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String username, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(username).roles(role);
    }

    private List<AdminDashboardSummaryDto.CapacityAlert> alertsFromHttp(String username, String role) throws Exception {
        String json = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/dashboard/summary").with(as(username, role)))
                .andReturn().getResponse().getContentAsString();
        List<AdminDashboardSummaryDto.CapacityAlert> result = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode a : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("capacityAlerts")) {
            result.add(new AdminDashboardSummaryDto.CapacityAlert(a.get("departmentCode").asText(), a.get("departmentName").asText(), a.get("remainingMinutes").asInt(),
                    a.get("capacityMinutes").asLong(), a.get("eligibleStaff").asInt(), a.get("utilizationPercent").isNull() ? null : a.get("utilizationPercent").asDouble(),
                    a.get("thresholdPercent").asInt(), a.get("noCapacity").asBoolean()));
        }
        return result;
    }

    private org.springframework.mock.web.MockHttpServletResponse post(String url, String username, String role, Object body) throws Exception {
        return mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(as(username, role))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body))).andReturn().getResponse();
    }

    // ── Scope: Admin global, Manager own department ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin gets capacity alerts across departments; a Manager gets only their own department's, never another's")
    void adminSeesAllManagerSeesOwnDepartmentOnly() throws Exception {
        Department mine = departmentLoad("M", 2, new int[]{480, 480}, NONE);       // 100%
        Department other = departmentLoad("O", 2, new int[]{480, 480}, NONE);      // 100%
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("s4d_manager", mine);
        settings(true, true, true, 100);

        java.util.Set<String> adminCodes = alertsFromHttp("s4d_admin", "ADMIN").stream().map(AdminDashboardSummaryDto.CapacityAlert::departmentCode).collect(Collectors.toSet());
        assertTrue(adminCodes.contains(mine.getCode()) && adminCodes.contains(other.getCode()), "the Admin sees both departments");

        List<AdminDashboardSummaryDto.CapacityAlert> managerAlerts = alertsFromHttp("s4d_manager", "MANAGER");
        assertEquals(List.of(mine.getCode()), managerAlerts.stream().map(AdminDashboardSummaryDto.CapacityAlert::departmentCode).toList());
        assertEquals(2, managerAlerts.get(0).eligibleStaff(), "the Manager's own Staff record is not capacity: only the two Staff-role people count");

        settings(true, false, true, 100);
        assertTrue(alertsFromHttp("s4d_manager", "MANAGER").isEmpty(), "alert OFF: none for the Manager either");
    }

    // ── Cross-Department Assistance through the real HTTP endpoints ──────────────────────────────────────────

    @Test
    @DisplayName("OFF over HTTP: preview (Admin and Manager) and confirm both answer 409 with the settings message; ON answers normally")
    void crossDepartmentOffEnforcedThroughHttp() throws Exception {
        var s = fx.overloaded(2, 2);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("s4d_receiving_manager", s.receiving);
        SupportPlanRequest plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        long supportBefore = supportRepository.count();
        long assignmentsBefore = assignmentRepository.count();

        settings(true, true, false, 85);
        for (String[] caller : new String[][] {{"s4d_admin", "ADMIN"}, {"s4d_receiving_manager", "MANAGER"}}) {
            var preview = post("/api/support/preview", caller[0], caller[1], plan);
            assertEquals(409, preview.getStatus(), caller[1] + " preview");
            assertTrue(preview.getContentAsString().contains("turned off in System Settings"));
            assertTrue(preview.getContentAsString().contains("CROSS_DEPARTMENT_ASSISTANCE_DISABLED"));
        }
        var confirm = post("/api/support/assignments", "s4d_admin", "ADMIN", plan);
        assertEquals(409, confirm.getStatus());
        assertTrue(confirm.getContentAsString().contains("CROSS_DEPARTMENT_ASSISTANCE_DISABLED"));
        assertEquals(supportBefore, supportRepository.count());
        assertEquals(assignmentsBefore, assignmentRepository.count());

        settings(true, true, true, 85);
        assertEquals(200, post("/api/support/preview", "s4d_admin", "ADMIN", plan).getStatus());
        assertEquals(201, post("/api/support/assignments", "s4d_admin", "ADMIN", plan).getStatus());
    }

    @Test
    @DisplayName("OFF over HTTP: existing support can still be viewed and ended, and is not cancelled by the setting")
    void existingSupportViewableAndEndableOverHttpWhenOff() throws Exception {
        var s = fx.overloaded(2, 2);
        settings(true, true, true, 85);
        SupportAssignmentDto created = support.confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).get(0);
        settings(true, true, false, 85);

        var list = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/support/assignments").with(as("s4d_admin", "ADMIN")))
                .andReturn().getResponse();
        assertEquals(200, list.getStatus());
        assertTrue(list.getContentAsString().contains(created.id().toString()));
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(created.id()).orElseThrow().getStatus());
        var end = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/support/assignments/" + created.id() + "/end").with(as("s4d_admin", "ADMIN")))
                .andReturn().getResponse();
        assertEquals(200, end.getStatus());
    }
}
