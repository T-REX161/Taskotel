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
import com.hotel.workload.workload.WorkloadController;
import com.hotel.workload.workload.WorkloadDto;
import com.hotel.workload.workload.WorkloadRecommendationService;
import com.hotel.workload.workload.WorkloadService;
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
    @Autowired private WorkloadService workloadService;
    @Autowired private WorkloadController workloadController;
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

    // ── Capacity alert and threshold ─────────────────────────────────────────────────────────────────────────

    /** A department with four operational staff, one of them (25%) in work, plus a busy Manager and an inactive busy person who are not counted. */
    private Department quarterBusyDepartment() {
        Department department = fx.department("C");
        Staff busy = fx.eligible("Busy Bea", department);
        fx.eligible("Free Fay", department);
        fx.eligible("Free Finn", department);
        fx.eligible("Free Flo", department);
        fx.assign(fx.waiting("Counts", department, Priority.MEDIUM, 30), busy);
        Staff manager = fx.person("Busy Manager", department, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        fx.assign(fx.waiting("Manager job", department, Priority.MEDIUM, 30), manager);
        Staff inactive = fx.person("Busy Inactive", department, Role.STAFF, true, false, true, Availability.AVAILABLE, false);
        fx.assign(fx.waiting("Inactive job", department, Priority.MEDIUM, 30), inactive);
        return department;
    }

    private List<AdminDashboardSummaryDto.CapacityAlert> alertsFor(Department department) {
        AdminDashboardSummaryDto summary = (AdminDashboardSummaryDto) dashboard.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        return summary.getCapacityAlerts().stream().filter(a -> a.departmentCode().equals(department.getCode())).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Capacity alert ON: the department at or above the threshold is reported once, counting only active operational Staff")
    void alertShownWhenEnabledAndThresholdReached() {
        Department department = quarterBusyDepartment();
        settings(true, true, true, 25);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size(), "one warning per department");
        AdminDashboardSummaryDto.CapacityAlert alert = alerts.get(0);
        assertEquals(1, alert.staffInWork(), "the busy Manager and the inactive person are not counted");
        assertEquals(4, alert.operationalStaff());
        assertEquals(25, alert.percentInWork());
        assertEquals(25, alert.thresholdPercent());
    }

    @Test
    @DisplayName("Capacity alert OFF: no warnings at all, whatever the load")
    void alertSuppressedWhenDisabled() {
        Department department = quarterBusyDepartment();
        settings(true, false, true, 1);
        assertTrue(alertsFor(department).isEmpty());
        AdminDashboardSummaryDto summary = (AdminDashboardSummaryDto) dashboard.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(summary.getCapacityAlerts().isEmpty());
    }

    @Test
    @DisplayName("The threshold is the real condition: 25% in work warns at 25 but not at 26")
    void thresholdChangesTheWarningCondition() {
        Department department = quarterBusyDepartment();
        settings(true, true, true, 26);
        assertTrue(alertsFor(department).isEmpty());
        settings(true, true, true, 25);
        assertEquals(1, alertsFor(department).size());
        settings(true, true, true, 10);
        assertEquals(1, alertsFor(department).size());
        assertEquals(10, alertsFor(department).get(0).thresholdPercent());
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

    // ── Capacity formula: active assigned operational Staff / active AND available operational Staff x 100 ─────────

    /** {@code available} AVAILABLE people (the first {@code busyAvailable} of them in work) and {@code unavailable} UNAVAILABLE people. */
    private Department departmentWith(String label, int available, int busyAvailable, int unavailable) {
        Department department = fx.department(label);
        for (int i = 0; i < available; i++) {
            Staff member = fx.eligible("Avail " + label + i, department);
            if (i < busyAvailable) fx.assign(fx.waiting("Job " + label + i, department, Priority.MEDIUM, 30), member);
        }
        for (int i = 0; i < unavailable; i++) {
            fx.person("Away " + label + i, department, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        }
        return department;
    }

    @Test
    @DisplayName("Unavailable staff are left out of the denominator: 1 of 2 available in work is 50% however many are away")
    void unavailableStaffExcludedFromDenominator() {
        Department department = departmentWith("U", 2, 1, 3);
        settings(true, true, true, 50);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size(), "1 of 2 available reaches 50% (it would be 20% if the 3 away were counted)");
        assertEquals(2, alerts.get(0).operationalStaff());
        assertEquals(1, alerts.get(0).staffInWork());
        assertEquals(50, alerts.get(0).percentInWork());
        settings(true, true, true, 51);
        assertTrue(alertsFor(department).isEmpty());
    }

    @Test
    @DisplayName("A department with nobody available has no denominator: no warning and no division by zero")
    void nobodyAvailableNeverWarnsOrFails() {
        Department department = departmentWith("N", 0, 0, 2);
        settings(true, true, true, 1);
        assertTrue(alertsFor(department).isEmpty());
    }

    @Test
    @DisplayName("Active, available staff with assignments are counted exactly: 3 of 4 is 75%, at the threshold warns, one above does not")
    void availableAssignedStaffCountedAtAndAroundThreshold() {
        Department department = departmentWith("A", 4, 3, 0);
        settings(true, true, true, 75);
        assertEquals(75, alertsFor(department).get(0).percentInWork());
        settings(true, true, true, 76);
        assertTrue(alertsFor(department).isEmpty());
        settings(true, true, true, 74);
        assertEquals(1, alertsFor(department).size());
    }

    @Test
    @DisplayName("Regression: an unavailable person holding an active assignment does not raise the numerator (1 of 2 stays 50%, not 2 of 3)")
    void unavailableStaffWithActiveAssignmentDoesNotIncreaseNumerator() {
        Department department = departmentWith("R", 2, 1, 0);
        Staff away = fx.person("Away But Busy", department, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        fx.assign(fx.waiting("Away job", department, Priority.MEDIUM, 30), away);
        settings(true, true, true, 50);
        AdminDashboardSummaryDto.CapacityAlert alert = alertsFor(department).get(0);
        assertEquals(1, alert.staffInWork(), "the unavailable person's assignment is not counted");
        assertEquals(2, alert.operationalStaff());
        assertEquals(50, alert.percentInWork());
        settings(true, true, true, 51);
        assertTrue(alertsFor(department).isEmpty(), "it would warn at 51 if the unavailable person were in the numerator (67%)");
    }

    @Test
    @DisplayName("Only active, available Staff-role people with a department count; inactive, Manager, Admin and department-less people with work are in neither number, and 100% is the ceiling")
    void ineligiblePeopleAreInNeitherNumberAndPercentageNeverExceedsHundred() {
        Department department = departmentWith("E", 2, 2, 0);
        Staff inactive = fx.person("Inactive Busy", department, Role.STAFF, true, false, true, Availability.AVAILABLE, false);
        Staff manager = fx.person("Manager Busy", department, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        Staff admin = fx.person("Admin Busy", department, Role.ADMIN, true, false, true, Availability.AVAILABLE, true);
        Staff noDepartment = fx.person("No Department Busy", null, Role.STAFF, true, false, true, Availability.AVAILABLE, true);
        Staff away = fx.person("Away Busy", department, Role.STAFF, true, false, true, Availability.UNAVAILABLE, true);
        for (Staff ineligible : List.of(inactive, manager, admin, noDepartment, away)) {
            fx.assign(fx.waiting("Work of " + ineligible.getName(), department, Priority.MEDIUM, 30), ineligible);
        }
        settings(true, true, true, 100);
        List<AdminDashboardSummaryDto.CapacityAlert> alerts = alertsFor(department);
        assertEquals(1, alerts.size());
        assertEquals(2, alerts.get(0).staffInWork());
        assertEquals(2, alerts.get(0).operationalStaff());
        assertEquals(100, alerts.get(0).percentInWork());
        AdminDashboardSummaryDto summary = (AdminDashboardSummaryDto) dashboard.getDashboardSummary(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(summary.getCapacityAlerts().stream().allMatch(a -> a.percentInWork() <= 100 && a.staffInWork() <= a.operationalStaff()), "never above 100%");
    }

    @Test
    @DisplayName("Available staff without assignments count only in the denominator: 1 of 5 is 20%")
    void freeAvailableStaffCountOnlyInDenominator() {
        Department department = departmentWith("F", 5, 1, 0);
        settings(true, true, true, 20);
        AdminDashboardSummaryDto.CapacityAlert alert = alertsFor(department).get(0);
        assertEquals(1, alert.staffInWork());
        assertEquals(5, alert.operationalStaff());
        assertEquals(20, alert.percentInWork());
    }

    @Test
    @DisplayName("85% threshold: 4, 8 of 10 do not warn; 9 and 10 of 10 do")
    void spec_exampleAtEightyFivePercent() {
        settings(true, true, true, 85);
        assertTrue(alertsFor(departmentWith("S4", 10, 4, 0)).isEmpty(), "40%");
        assertTrue(alertsFor(departmentWith("S8", 10, 8, 0)).isEmpty(), "80%");
        assertEquals(90, alertsFor(departmentWith("S9", 10, 9, 0)).get(0).percentInWork());
        assertEquals(100, alertsFor(departmentWith("S10", 10, 10, 0)).get(0).percentInWork());
    }

    @Test
    @DisplayName("Boundaries 1, 50 and 100: warns when the percentage equals the threshold, not when it is one below")
    void spec_thresholdBoundaries() {
        Department oneOfHundred = departmentWith("B1", 100, 1, 0);
        Department half = departmentWith("B50", 2, 1, 0);
        Department full = departmentWith("B100", 3, 3, 0);
        Department almostFull = departmentWith("B99", 3, 2, 0);

        settings(true, true, true, 1);
        assertEquals(1, alertsFor(oneOfHundred).size(), "exactly 1%");
        assertEquals(1, alertsFor(half).size());
        settings(true, true, true, 50);
        assertEquals(1, alertsFor(half).size(), "exactly 50%");
        assertTrue(alertsFor(oneOfHundred).isEmpty(), "1% is below 50%");
        settings(true, true, true, 100);
        assertEquals(1, alertsFor(full).size(), "exactly 100%");
        assertTrue(alertsFor(almostFull).isEmpty(), "66% is below 100%");
        assertTrue(alertsFor(half).isEmpty());
    }

    @Test
    @DisplayName("One person with several active assignments counts once, not once per assignment")
    void multipleAssignmentsForOnePersonCountOnce() {
        Department department = fx.department("MA");
        Staff busy = fx.eligible("Many Jobs", department);
        for (int i = 0; i < 3; i++) fx.assign(fx.waiting("Job " + i, department, Priority.MEDIUM, 30), busy);
        fx.eligible("Free 1", department);
        fx.eligible("Free 2", department);
        fx.eligible("Free 3", department);
        settings(true, true, true, 25);
        AdminDashboardSummaryDto.CapacityAlert alert = alertsFor(department).get(0);
        assertEquals(1, alert.staffInWork());
        assertEquals(4, alert.operationalStaff());
        assertEquals(25, alert.percentInWork());
        settings(true, true, true, 26);
        assertTrue(alertsFor(department).isEmpty(), "three assignments must not look like 75%");
    }

    @Test
    @DisplayName("Only PENDING, ASSIGNED and IN_PROGRESS tasks are active work; COMPLETED and CANCELLED are not")
    void onlyActiveStatusesCount() {
        Department department = fx.department("ST");
        Staff done = fx.eligible("Done", department);
        Staff cancelled = fx.eligible("Cancelled", department);
        Staff inProgress = fx.eligible("In progress", department);
        fx.eligible("Idle", department);
        Task completedTask = fx.waiting("Completed", department, Priority.MEDIUM, 30);
        fx.assign(completedTask, done);
        completedTask.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(completedTask);
        Task cancelledTask = fx.waiting("Cancelled", department, Priority.MEDIUM, 30);
        fx.assign(cancelledTask, cancelled);
        cancelledTask.setStatus(TaskStatus.CANCELLED);
        taskRepository.saveAndFlush(cancelledTask);
        Task activeTask = fx.waiting("Active", department, Priority.MEDIUM, 30);
        fx.assign(activeTask, inProgress);
        activeTask.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(activeTask);

        settings(true, true, true, 25);
        AdminDashboardSummaryDto.CapacityAlert alert = alertsFor(department).get(0);
        assertEquals(1, alert.staffInWork());
        assertEquals(4, alert.operationalStaff());
        settings(true, true, true, 26);
        assertTrue(alertsFor(department).isEmpty());
    }

    @Test
    @DisplayName("A department with no staff at all, and one whose staff are all unavailable, never warn")
    void emptyAndAllUnavailableDepartmentsNeverWarn() {
        Department empty = fx.department("EM");
        Department away = departmentWith("AW", 0, 0, 3);
        settings(true, true, true, 1);
        assertTrue(alertsFor(empty).isEmpty());
        assertTrue(alertsFor(away).isEmpty());
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
            result.add(new AdminDashboardSummaryDto.CapacityAlert(a.get("departmentCode").asText(), a.get("departmentName").asText(), a.get("staffInWork").asInt(),
                    a.get("operationalStaff").asInt(), a.get("percentInWork").asInt(), a.get("thresholdPercent").asInt()));
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
        Department mine = departmentWith("M", 2, 2, 0);
        Department other = departmentWith("O", 2, 2, 0);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).manager("s4d_manager", mine);
        settings(true, true, true, 100);

        java.util.Set<String> adminCodes = alertsFromHttp("s4d_admin", "ADMIN").stream().map(AdminDashboardSummaryDto.CapacityAlert::departmentCode).collect(Collectors.toSet());
        assertTrue(adminCodes.contains(mine.getCode()) && adminCodes.contains(other.getCode()), "the Admin sees both departments");

        List<AdminDashboardSummaryDto.CapacityAlert> managerAlerts = alertsFromHttp("s4d_manager", "MANAGER");
        assertEquals(List.of(mine.getCode()), managerAlerts.stream().map(AdminDashboardSummaryDto.CapacityAlert::departmentCode).toList());

        settings(true, false, true, 100);
        assertTrue(alertsFromHttp("s4d_manager", "MANAGER").isEmpty(), "alert OFF: none for the Manager either");
    }

    // ── Department-risk threshold (colours the Workload by Department bars) ──────────────────────────────────

    private com.fasterxml.jackson.databind.JsonNode riskThreshold(String username, String role) throws Exception {
        var response = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workload/department-risk-threshold").with(as(username, role)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus(), role);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
    }

    @Test
    @DisplayName("The department-risk threshold endpoint returns the saved value to Admin and Manager, follows every change, and exposes nothing else")
    void riskThresholdEndpointFollowsTheSavedValue() throws Exception {
        Department mine = departmentWith("RT", 1, 0, 0);
        ScopeFixtures scope = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        scope.manager("s4d_rt_manager", mine);
        for (int threshold : new int[]{10, 1, 50, 85, 100}) {
            settings(true, true, true, threshold);
            for (String[] who : new String[][]{{"s4d_admin", "ADMIN"}, {"s4d_rt_manager", "MANAGER"}}) {
                var body = riskThreshold(who[0], who[1]);
                assertEquals(threshold, body.get("capacityThresholdPercent").asInt(), who[1] + " sees the saved threshold");
                assertTrue(body.get("enabled").asBoolean());
                assertEquals(2, body.size(), "only the switch and the percentage, nothing else from System Settings");
            }
        }
        settings(true, false, true, 10);
        assertFalse(riskThreshold("s4d_admin", "ADMIN").get("enabled").asBoolean(), "switched off is reported, so nothing is overridden");
    }

    @Test
    @DisplayName("With nothing saved the endpoint reports the default 85; Staff, an invalid Manager and anonymous callers are refused; the settings API stays Admin only")
    void riskThresholdDefaultAnonymousAndSettingsStayLocked() throws Exception {
        settingsRepository.deleteAll();
        settingsRepository.flush();
        assertEquals(85, riskThreshold("s4d_admin", "ADMIN").get("capacityThresholdPercent").asInt());
        assertEquals(401, mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workload/department-risk-threshold").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andReturn().getResponse().getStatus());
        // Staff have their own page without department cards: they get no department-level information, and no threshold
        assertEquals(403, mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workload/department-risk-threshold").with(as("s4d_staff", "STAFF")))
                .andReturn().getResponse().getStatus(), "Staff are refused at the URL");
        // A Manager account with no valid department is refused by the scope check as well (403, not an empty answer)
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).account("s4d_invalid_manager", Role.MANAGER, true);
        assertEquals(403, mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workload/department-risk-threshold").with(as("s4d_invalid_manager", "MANAGER")))
                .andReturn().getResponse().getStatus(), "an invalid Manager is refused");
        for (String role : new String[]{"MANAGER", "STAFF"}) {
            assertEquals(403, mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/system-settings").with(as("someone", role)))
                    .andReturn().getResponse().getStatus(), role + " still cannot read System Settings");
            assertEquals(403, mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/system-settings").with(as("someone", role))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"autoBalancingEnabled\":true,\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,\"capacityThresholdPercent\":10}"))
                    .andReturn().getResponse().getStatus(), role + " still cannot change System Settings");
        }
    }

    @Test
    @DisplayName("Defence in depth: calling the controller directly as a real Staff member is refused too, with nothing returned")
    void riskThresholdControllerRefusesStaffWithoutTheUrlRule() {
        Department department = fx.department("RS");
        Staff member = fx.eligible("Plain Staff", department);
        SupportTestFixture.signInAs(member.getUser().getUsername(), "STAFF");
        var refused = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> workloadController.getDepartmentRiskThreshold());
        assertEquals(403, refused.getStatusCode().value());
        SupportTestFixture.signInAs("s4d_admin", "ADMIN");
        assertEquals(200, workloadController.getDepartmentRiskThreshold().getStatusCode().value(), "the Admin is served");
    }

    // ── AT RISK (share of total workload) is NOT the same as needing support (uncovered tasks vs eligible donors) ─────

    @Test
    @DisplayName("A department can be AT RISK by workload share without needing support, and the Capacity Threshold never changes who needs support")
    void atRiskDoesNotMeanNeedsSupport() {
        var s = fx.overloaded(2, 3);                                  // receiving: 2 waiting tasks, nobody free -> needs support; donor: 3 free
        Department heavy = fx.department("H");                        // heavy: two free people and one big assigned task -> a large share of the minutes
        Staff busyOne = fx.eligible("Heavy Busy", heavy);
        fx.eligible("Heavy Free 1", heavy);
        fx.eligible("Heavy Free 2", heavy);
        fx.assign(fx.waiting("Big job", heavy, Priority.MEDIUM, 400), busyOne);

        SupportTestFixture.signInAs("s4d_admin", "ADMIN");
        List<WorkloadDto> rows = workloadService.calculateWorkload();
        int total = rows.stream().mapToInt(WorkloadDto::getTotalEstimatedDuration).sum();
        int heavyMinutes = rows.stream().filter(r -> heavy.getCode().equalsIgnoreCase(r.getSupportDepartment() != null ? r.getSupportDepartment() : r.getDepartment()))
                .mapToInt(WorkloadDto::getTotalEstimatedDuration).sum();
        long heavyShare = Math.round(100.0 * heavyMinutes / total);
        assertTrue(heavyShare >= 25, "the heavy department holds a large share of the load (" + heavyShare + "%), so it is AT RISK for any threshold at or below that");

        java.util.Set<String> needing = null;
        for (int threshold : new int[]{1, 25, 50, 85, 100}) {
            settings(true, true, true, threshold);
            WorkloadRecommendationsDto result = recommendations.getRecommendations();
            java.util.Set<String> codes = new java.util.TreeSet<>();
            result.recommendations().forEach(r -> codes.add(r.departmentCode()));
            result.unsupported().forEach(u -> codes.add(u.departmentCode()));
            assertTrue(codes.contains(s.receiving.getCode()), "uncovered tasks make the receiver need support at threshold " + threshold);
            assertFalse(codes.contains(heavy.getCode()), "a department with enough free staff does not need support at threshold " + threshold + ", AT RISK or not");
            if (needing == null) needing = codes;
            assertEquals(needing, codes, "the Capacity Threshold does not change who needs support (threshold " + threshold + ")");
            var receiver = result.recommendations().stream().filter(r -> r.departmentCode().equals(s.receiving.getCode())).findFirst().orElseThrow();
            assertEquals(2, receiver.pressure().uncoveredTasks());
        }
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
