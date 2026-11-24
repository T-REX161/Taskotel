package com.hotel.workload.support;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.Options;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskService;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.task.UpdateTaskRequest;
import com.hotel.workload.workload.DepartmentCapacityDto;
import com.hotel.workload.workload.DepartmentCapacityService;
import com.hotel.workload.workload.WorkloadRecommendationService;
import com.hotel.workload.workload.WorkloadRecommendationsDto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A department at or above its capacity limit with no waiting task can still be helped: a helper JOINS a task its own staff already hold. The
 * helper adds capacity and no minutes, the task keeps its owner and status, and ending (or completing) releases only the helper.
 */
@SpringBootTest
@Transactional
public class SharedTaskSupportTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private DepartmentCapacityService capacity;
    @Autowired private WorkloadRecommendationService recommendations;
    @Autowired private TaskService taskService;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    private SupportTestFixture fx;
    private ScopeFixtures scope;
    private int worlds = 0;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        scope = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        User admin = scope.account("share_admin", Role.ADMIN, true);
        SupportTestFixture.signInAs("share_admin", "ADMIN");
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(true);
        row.setCapacityAlertEnabled(true);
        row.setCrossDepartmentAssistanceEnabled(true);
        row.setCapacityThresholdPercent(85);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedByUserId(admin.getId());
        row.setUpdatedByUsername(admin.getUsername());
        settingsRepository.saveAndFlush(row);
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    private static final class World {
        Department receiving;
        Department donor;
        Staff owner;
        Task held;
        List<Staff> donors;
    }

    /** One person holding one task of {@code minutes}, nothing waiting; a donor with four free people. */
    private World world(int minutes) {
        World w = new World();
        int n = worlds++;
        w.receiving = fx.department("SR" + n);
        w.donor = fx.department("SD" + n);
        w.owner = fx.eligible("Owner " + n, w.receiving);
        w.held = fx.task("Held " + n, w.receiving, Priority.MEDIUM, minutes, TaskStatus.PENDING);
        fx.assign(w.held, w.owner);
        w.donors = List.of(fx.eligible("Donor a" + n, w.donor), fx.eligible("Donor b" + n, w.donor), fx.eligible("Donor c" + n, w.donor), fx.eligible("Donor d" + n, w.donor));
        return w;
    }

    private DepartmentCapacityDto.Entry entry(Department department) {
        entityManager.flush();
        entityManager.clear();
        return capacity.forCaller().departments().stream().filter(e -> e.code().equals(department.getCode())).findFirst().orElseThrow();
    }

    private UpdateTaskRequest update(Task task, TaskStatus status) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle(task.getTitle());
        request.setPriority(task.getPriority());
        request.setEstimatedDuration(task.getEstimatedDuration());
        request.setDueDate(task.getDueDate());
        request.setDepartment(task.getDepartment().getCode());
        request.setStatus(status);
        return request;
    }

    @Test
    @DisplayName("At its limit with every task already held, the department offers those tasks to join and accepts one helper per task")
    void atRiskDepartmentOffersHeldTasks() {
        World w = world(500);                                    // 500 min on one person = 104.2%
        assertTrue(entry(w.receiving).atRisk());
        Options options = service.getOptions(w.receiving.getCode());
        assertEquals(1, options.tasks().size());
        assertTrue(options.tasks().get(0).shared(), "marked as a task its own staff hold");
        assertEquals(1, options.maxHelpers(), "one more person brings 500 / 960 = 52.1%, below the limit");
    }

    @Test
    @DisplayName("Joining adds capacity and no minutes; the task keeps its owner and status; the department falls below its limit")
    void joiningAddsCapacityNotMinutes() {
        World w = world(500);
        DepartmentCapacityDto.Entry before = entry(w.receiving);
        SupportAssignmentDto record = service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held)).get(0);

        DepartmentCapacityDto.Entry after = entry(w.receiving);
        assertEquals(before.remainingMinutes(), after.remainingMinutes(), "a helper never changes the minutes");
        assertEquals(before.assignedMinutes(), after.assignedMinutes(), "the task is counted once, not once per person");
        assertEquals(2, after.eligibleStaff());
        assertEquals(52.1, after.utilizationPercent());
        assertFalse(after.atRisk());
        assertEquals(3, entry(w.donor).eligibleStaff());

        Task task = taskRepository.findById(w.held.getId()).orElseThrow();
        assertEquals(TaskStatus.ASSIGNED, task.getStatus(), "the owner's task keeps its status");
        List<Assignment> holders = assignmentRepository.findAllByTaskId(w.held.getId());
        assertEquals(2, holders.size());
        assertTrue(holders.stream().anyMatch(a -> a.getStaff().getId().equals(w.owner.getId())), "the owner still holds it");
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(record.id()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Option A: a task a Staff member accepted themselves takes no helper (not offered, and a plan naming it is refused); the same task assigned by a Manager still can")
    void selfClaimedTaskTakesNoHelper() {
        World w = world(500);
        assertEquals(1, service.getOptions(w.receiving.getCode()).tasks().size(), "assigned by a Manager: offered, as before");

        Assignment owned = assignmentRepository.findAllByTaskId(w.held.getId()).get(0);
        owned.setSelfClaimed(true);
        assignmentRepository.saveAndFlush(owned);

        assertTrue(service.getOptions(w.receiving.getCode()).tasks().isEmpty(), "a self-claimed task is never offered for a helper");
        SupportPlanException refused = assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held)));
        assertTrue(refused.getMessage().contains("belongs to them alone"), refused.getMessage());
        assertEquals(1, assignmentRepository.findAllByTaskId(w.held.getId()).size(), "nobody was added");
        assertTrue(supportRepository.findByTaskIdAndStatus(w.held.getId(), SupportStatus.ACTIVE).isEmpty());
    }

    @Test
    @DisplayName("Option A: a Manager of the receiving department (the narrowed load) is not offered a self-claimed task either, and is offered the same task when a Manager assigned it")
    void selfClaimedTaskIsNotOfferedToAManager() {
        World w = world(500);
        scope.manager("share_manager_opt", w.receiving);
        SupportTestFixture.signInAs("share_manager_opt", "MANAGER");
        assertEquals(1, service.getOptions(w.receiving.getCode()).tasks().size(), "assigned by a Manager: offered, as before");

        SupportTestFixture.signInAs("share_admin", "ADMIN");
        Assignment owned = assignmentRepository.findAllByTaskId(w.held.getId()).get(0);
        owned.setSelfClaimed(true);
        assignmentRepository.saveAndFlush(owned);
        entityManager.clear();

        SupportTestFixture.signInAs("share_manager_opt", "MANAGER");
        assertTrue(service.getOptions(w.receiving.getCode()).tasks().isEmpty(), "self-claimed: never offered for a helper");
    }

    @Test
    @DisplayName("Option A: the workload recommendations never suggest a helper for a self-claimed task, while the same task assigned by a Manager is still suggested")
    void recommendationsNeverSuggestASelfClaimedTask() {
        World w = world(500);
        java.util.function.Supplier<Boolean> suggested = () -> recommendations.getRecommendations().recommendations().stream()
                .filter(r -> r.departmentCode().equals(w.receiving.getCode()))
                .flatMap(r -> r.suggestions().stream())
                .anyMatch(s -> w.held.getTitle().equals(s.coversTaskTitle()));
        assertTrue(suggested.get(), "assigned by a Manager: suggested, as before");

        Assignment owned = assignmentRepository.findAllByTaskId(w.held.getId()).get(0);
        owned.setSelfClaimed(true);
        assignmentRepository.saveAndFlush(owned);
        entityManager.clear();
        assertFalse(suggested.get(), "self-claimed: belongs to its owner alone");
    }

    @Test
    @DisplayName("A task takes one helper: a second plan for the same task is refused")
    void oneHelperPerTask() {
        World w = world(900);
        service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held));
        assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(w.receiving, w.donors.get(1), w.held)));
        assertEquals(2, entry(w.receiving).eligibleStaff(), "the refused plan changed nothing");
    }

    @Test
    @DisplayName("A department below its limit cannot use the held tasks of its own staff")
    void notAtRiskCannotJoin() {
        World w = world(100);                                    // 100 / 480 = 20.8%
        assertFalse(entry(w.receiving).atRisk());
        assertTrue(service.getOptions(w.receiving.getCode()).tasks().isEmpty());
        assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held)));
    }

    @Test
    @DisplayName("Ending the support releases only the helper: the owner keeps the task and its status, and the capacity goes back")
    void endingReleasesOnlyTheHelper() {
        World w = world(500);
        DepartmentCapacityDto.Entry before = entry(w.receiving);
        SupportAssignmentDto record = service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held)).get(0);
        service.end(record.id());

        List<Assignment> holders = assignmentRepository.findAllByTaskId(w.held.getId());
        assertEquals(1, holders.size());
        assertEquals(w.owner.getId(), holders.get(0).getStaff().getId());
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(w.held.getId()).orElseThrow().getStatus(), "not put back in the waiting list");
        DepartmentCapacityDto.Entry after = entry(w.receiving);
        assertEquals(before.eligibleStaff(), after.eligibleStaff());
        assertEquals(before.utilizationPercent(), after.utilizationPercent());
        assertEquals(4, entry(w.donor).eligibleStaff());
        assertEquals(SupportStatus.CANCELLED, supportRepository.findById(record.id()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Completing the task ends the support and gives the capacity back")
    void completingTheTaskEndsTheSupport() {
        World w = world(500);
        SupportAssignmentDto record = service.confirm(fx.plan(w.receiving, w.donors.get(0), w.held)).get(0);
        scope.manager("share_manager", w.receiving);
        SupportTestFixture.signInAs("share_manager", "MANAGER");
        taskService.updateTask(w.held.getId(), update(w.held, TaskStatus.COMPLETED));
        SupportTestFixture.signInAs("share_admin", "ADMIN");

        assertEquals(SupportStatus.COMPLETED, supportRepository.findById(record.id()).orElseThrow().getStatus());
        assertEquals(0, entry(w.receiving).remainingMinutes());
        assertEquals(1, entry(w.receiving).eligibleStaff());
        assertEquals(4, entry(w.donor).eligibleStaff());
    }

    @Test
    @DisplayName("The recommendations suggest a helper for an at-risk department with nothing waiting, and say the helper joins a held task")
    void recommendationsSuggestJoining() {
        World w = world(500);
        WorkloadRecommendationsDto result = recommendations.getRecommendations();
        WorkloadRecommendationsDto.DepartmentRecommendation rec = result.recommendations().stream()
                .filter(r -> r.departmentCode().equals(w.receiving.getCode())).findFirst().orElseThrow();
        assertEquals(1, rec.suggestions().size());
        assertEquals(w.held.getTitle(), rec.suggestions().get(0).coversTaskTitle());
        assertTrue(rec.suggestions().get(0).reasons().stream().anyMatch(r -> r.contains("Could join")));
        assertTrue(result.unsupported().stream().noneMatch(u -> u.departmentCode().equals(w.receiving.getCode())));
    }

    @Test
    @DisplayName("A task held by two people counts its minutes once in the department's remaining minutes")
    void minutesCountedOncePerTask() {
        World w = world(500);
        Staff second = fx.eligible("Second owner", w.receiving);
        Assignment extra = new Assignment();
        extra.setTask(w.held);
        extra.setStaff(second);
        assignmentRepository.saveAndFlush(extra);
        DepartmentCapacityDto.Entry e = entry(w.receiving);
        assertEquals(500, e.remainingMinutes());
        assertEquals(500, e.assignedMinutes());
        assertEquals(1, e.activeTasks());
    }
}
