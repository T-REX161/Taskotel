package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.DepartmentImpact;
import com.hotel.workload.support.SupportDtos.DonorDepartment;
import com.hotel.workload.support.SupportDtos.Options;
import com.hotel.workload.support.SupportDtos.Preview;
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
import com.hotel.workload.workload.WorkloadRecommendationsDto.DepartmentRecommendation;
import com.hotel.workload.workload.WorkloadStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-Department Support judged by capacity, through the real services and database (rolled back, in-memory test database). One eligible person
 * is one standard shift of 480 minutes; utilization = remaining task minutes / (eligible people x 480). Support moves capacity between
 * departments and never changes anybody's remaining minutes.
 */
@SpringBootTest
@Transactional
public class DepartmentCapacitySupportTest {

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
    private User admin;

    @BeforeEach
    void setUp() {
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        scope = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        admin = scope.account("cap_admin", Role.ADMIN, true);
        SupportTestFixture.signInAs("cap_admin", "ADMIN");
        settings(true, true, 85);
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void settings(boolean alert, boolean crossDepartment, int threshold) {
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(true);
        row.setCapacityAlertEnabled(alert);
        row.setCrossDepartmentAssistanceEnabled(crossDepartment);
        row.setCapacityThresholdPercent(threshold);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedByUserId(admin.getId());
        row.setUpdatedByUsername(admin.getUsername());
        settingsRepository.saveAndFlush(row);
    }

    private static final class World {
        Department receiving;
        Department donor;
        final List<Staff> receivingStaff = new ArrayList<>();
        final List<Staff> donors = new ArrayList<>();
        final List<Task> heldReceiving = new ArrayList<>();
        final List<Task> waiting = new ArrayList<>();
    }

    /**
     * Receiving: {@code receivingStaff} eligible people, the first {@code held.length} each holding one task of those minutes, plus waiting
     * tasks of {@code waiting} minutes nobody holds. Donor: {@code donorFree} eligible free people.
     */
    private int worlds = 0;

    private World world(int receivingStaff, int[] held, int[] waiting, int donorFree) {
        World w = new World();
        int n = worlds++;                                  // a test may build several scenarios: each gets its own departments
        w.receiving = fx.department("R" + n);
        w.donor = fx.department("D" + n);
        for (int i = 0; i < receivingStaff; i++) w.receivingStaff.add(fx.eligible("R person " + i, w.receiving));
        for (int i = 0; i < held.length; i++) {
            w.heldReceiving.add(fx.task("Held " + i, w.receiving, Priority.MEDIUM, held[i], TaskStatus.PENDING));
            fx.assign(w.heldReceiving.get(i), w.receivingStaff.get(i));
        }
        for (int i = 0; i < waiting.length; i++) w.waiting.add(fx.waiting("Waiting " + i, w.receiving, i == 0 ? Priority.URGENT : Priority.MEDIUM, waiting[i]));
        for (int i = 0; i < donorFree; i++) w.donors.add(fx.eligible("D person " + i, w.donor));
        return w;
    }

    private DepartmentCapacityDto.Entry entry(Department department) {
        entityManager.flush();
        entityManager.clear();
        return capacity.forCaller().departments().stream().filter(e -> e.code().equals(department.getCode())).findFirst().orElseThrow();
    }

    private int eligibleTotal(World w) {
        return entry(w.receiving).eligibleStaff() + entry(w.donor).eligibleStaff();
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

    // ── the figures themselves ───────────────────────────────────────────────

    @Test
    @DisplayName("A department's figures: assigned + waiting minutes over eligible people x 480; the donor with no work is 0%")
    void figuresAreRemainingMinutesOverCapacity() {
        World w = world(2, new int[]{300, 100}, new int[]{200}, 4);
        DepartmentCapacityDto.Entry r = entry(w.receiving);
        assertEquals(400, r.assignedMinutes());
        assertEquals(200, r.waitingMinutes());
        assertEquals(600, r.remainingMinutes());
        assertEquals(3, r.activeTasks());
        assertEquals(1, r.waitingTasks());
        assertEquals(2, r.eligibleStaff());
        assertEquals(960, r.capacityMinutes());
        assertEquals(62.5, r.utilizationPercent());
        assertEquals(WorkloadStatus.HIGH, r.level(), "62.5% is above 50% and below the 85% threshold");
        assertFalse(r.atRisk());
        DepartmentCapacityDto.Entry d = entry(w.donor);
        assertEquals(0, d.remainingMinutes());
        assertEquals(4, d.eligibleStaff());
        assertEquals(0.0, d.utilizationPercent());
        assertEquals(WorkloadStatus.LOW, d.level());
    }

    // ── confirming support ───────────────────────────────────────────────────

    @Test
    @DisplayName("Confirming support moves capacity from the donor to the receiver and leaves every remaining-minutes figure alone; no person is counted twice")
    void confirmingMovesCapacityNotMinutes() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);     // receiving: 1440 min on 2 people = 150%
        assertEquals(150.0, entry(w.receiving).utilizationPercent());
        assertTrue(entry(w.receiving).atRisk());
        assertEquals(6, eligibleTotal(w));

        // one helper
        SupportAssignmentDto first = service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0))).get(0);
        DepartmentCapacityDto.Entry r1 = entry(w.receiving);
        DepartmentCapacityDto.Entry d1 = entry(w.donor);
        assertEquals(1440, r1.remainingMinutes(), "the department's minutes did not fall because a helper joined");
        assertEquals(1200, r1.assignedMinutes(), "the helper's task moved from waiting to assigned");
        assertEquals(240, r1.waitingMinutes());
        assertEquals(3, r1.eligibleStaff());
        assertEquals(1, r1.helpersIn());
        assertEquals(100.0, r1.utilizationPercent(), "1440 / (3 x 480)");
        assertEquals(3, d1.eligibleStaff(), "the donor lost exactly the capacity the receiver gained");
        assertEquals(1, d1.lentOut());
        assertEquals(0, d1.remainingMinutes(), "the donor's minutes are untouched");
        assertEquals(6, eligibleTotal(w), "nobody counts in two departments at once");

        // a second helper
        service.confirm(fx.plan(w.receiving, w.donors.get(1), w.waiting.get(1)));
        DepartmentCapacityDto.Entry r2 = entry(w.receiving);
        assertEquals(1440, r2.remainingMinutes());
        assertEquals(4, r2.eligibleStaff());
        assertEquals(75.0, r2.utilizationPercent(), "1440 / (4 x 480)");
        assertEquals(WorkloadStatus.HIGH, r2.level(), "below the 85% threshold at last");
        assertFalse(r2.atRisk());
        assertEquals(2, entry(w.donor).eligibleStaff());
        assertEquals(6, eligibleTotal(w));
        assertNotNull(first.id());
    }

    @Test
    @DisplayName("Ending support gives the capacity back to the home department; the minutes are the same as before it started")
    void endingRestoresHomeCapacity() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        DepartmentCapacityDto.Entry before = entry(w.receiving);
        List<SupportAssignmentDto> created = new ArrayList<>();
        created.addAll(service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0))));
        created.addAll(service.confirm(fx.plan(w.receiving, w.donors.get(1), w.waiting.get(1))));
        assertEquals(4, entry(w.receiving).eligibleStaff());

        for (SupportAssignmentDto record : created) service.end(record.id());

        DepartmentCapacityDto.Entry r = entry(w.receiving);
        DepartmentCapacityDto.Entry d = entry(w.donor);
        assertEquals(before.eligibleStaff(), r.eligibleStaff(), "the receiver is back to its own people");
        assertEquals(0, r.helpersIn());
        assertEquals(before.remainingMinutes(), r.remainingMinutes(), "the tasks are waiting again, so the minutes are the same");
        assertEquals(before.waitingMinutes(), r.waitingMinutes());
        assertEquals(before.utilizationPercent(), r.utilizationPercent());
        assertEquals(4, d.eligibleStaff(), "the donor has its people back");
        assertEquals(0, d.lentOut());
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(x -> x.getReceivingDepartmentCode().equals(w.receiving.getCode())).count());
    }

    @Test
    @DisplayName("Completing tasks lowers the remaining minutes and recalculates utilization; completing the supported task also ends the support and releases the helper")
    void completingTasksLowersRemainingMinutes() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        assertEquals(1440, entry(w.receiving).remainingMinutes());

        scope.manager("cap_manager", w.receiving);
        SupportTestFixture.signInAs("cap_manager", "MANAGER");
        taskService.updateTask(w.heldReceiving.get(0).getId(), update(w.heldReceiving.get(0), TaskStatus.COMPLETED));      // a 480-minute task of the department's own people
        SupportTestFixture.signInAs("cap_admin", "ADMIN");
        DepartmentCapacityDto.Entry afterOwn = entry(w.receiving);
        assertEquals(960, afterOwn.remainingMinutes(), "1440 - 480");
        assertEquals(3, afterOwn.eligibleStaff(), "the helper is still helping");
        assertEquals(1, afterOwn.helpersIn());
        assertEquals(66.7, afterOwn.utilizationPercent(), "960 / (3 x 480)");

        SupportTestFixture.signInAs("cap_manager", "MANAGER");
        taskService.updateTask(w.waiting.get(0).getId(), update(w.waiting.get(0), TaskStatus.COMPLETED));                   // the task the helper took (240 min)
        SupportTestFixture.signInAs("cap_admin", "ADMIN");
        DepartmentCapacityDto.Entry afterSupported = entry(w.receiving);
        assertEquals(720, afterSupported.remainingMinutes(), "960 - 240");
        assertEquals(2, afterSupported.eligibleStaff(), "the support ended with its task: the helper is released");
        assertEquals(0, afterSupported.helpersIn());
        assertEquals(75.0, afterSupported.utilizationPercent(), "720 / (2 x 480)");
        assertEquals(4, entry(w.donor).eligibleStaff(), "back at home");
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(x -> x.getReceivingDepartmentCode().equals(w.receiving.getCode())).count());
    }

    @Test
    @DisplayName("A helper who becomes unavailable counts nowhere: not for the department they help, not for their home")
    void unavailableHelperCountsNowhere() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        Staff helper = w.donors.get(0);
        service.confirm(fx.plan(w.receiving, helper, w.waiting.get(0)));
        assertEquals(3, entry(w.receiving).eligibleStaff());

        helper.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(helper);

        DepartmentCapacityDto.Entry r = entry(w.receiving);
        DepartmentCapacityDto.Entry d = entry(w.donor);
        assertEquals(2, r.eligibleStaff(), "an unavailable person adds no capacity to the department they help");
        assertEquals(0, r.helpersIn());
        assertEquals(3, d.eligibleStaff(), "and is not capacity at home either: 4 people, one of them unavailable");
        assertEquals(0, d.lentOut());
    }

    @Test
    @DisplayName("One person cannot be booked twice, so their capacity is counted for the receiver once")
    void samePersonCannotBeCountedTwice() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        Staff helper = w.donors.get(0);
        service.confirm(fx.plan(w.receiving, helper, w.waiting.get(0)));

        Preview second = service.preview(fx.plan(w.receiving, helper, w.waiting.get(1)));
        assertFalse(second.valid());
        assertTrue(second.problems().stream().anyMatch(p -> "STAFF_BUSY".equals(p.code())), "a person holding a task cannot take another");
        assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(w.receiving, helper, w.waiting.get(1))));
        assertEquals(1, entry(w.receiving).helpersIn());
        assertEquals(3, entry(w.receiving).eligibleStaff());
    }

    // ── recommending, previewing and the donor limit ─────────────────────────

    @Test
    @DisplayName("A department at risk whose own free staff could take its waiting work is still offered a helper; preview and confirm agree and show the projected utilization")
    void riskAloneRecommendsPreviewsAndConfirms() {
        World w = world(3, new int[]{480, 480}, new int[]{480}, 4);          // 1440 min on 3 people = 100%; 1 waiting task and 1 free person: nothing uncovered

        WorkloadRecommendationsDto result = recommendations.getRecommendations();
        DepartmentRecommendation need = result.recommendations().stream().filter(r -> r.departmentCode().equals(w.receiving.getCode())).findFirst().orElse(null);
        assertNotNull(need, "needs support because of its utilization alone");
        assertEquals("HIGH_UTILIZATION", need.pressure().needReason());
        assertEquals(0, need.pressure().uncoveredTasks());
        assertEquals(100.0, need.pressure().utilizationPercent());
        assertEquals(1, need.suggestions().size());
        assertEquals(w.donor.getCode(), need.suggestions().get(0).homeDepartmentCode());
        assertEquals(75.0, need.suggestions().get(0).receiverImpact().utilizationAfter(), "1440 / (4 x 480)");

        Options options = service.getOptions(w.receiving.getCode());
        assertEquals("HIGH_UTILIZATION", options.receiving().needReason());
        assertEquals(100.0, options.receiving().metrics().utilizationPercent());
        assertEquals(1, options.maxHelpers(), "one waiting task, so at most one helper");
        DonorDepartment donor = options.donors().stream().filter(x -> x.code().equals(w.donor.getCode())).findFirst().orElseThrow();
        assertTrue(donor.canLend());
        assertEquals(0.0, donor.utilizationPercent());

        Preview preview = service.preview(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        assertTrue(preview.valid(), preview.problems().toString());
        assertEquals(100.0, preview.receiving().before().utilizationPercent());
        assertEquals(75.0, preview.receiving().after().utilizationPercent(), "the preview projects the receiver's utilization after the helper");
        assertEquals(1440, preview.receiving().before().remainingMinutes());
        assertEquals(1440, preview.receiving().after().remainingMinutes(), "the minutes do not change");
        assertEquals(3, preview.receiving().before().eligibleStaff());
        assertEquals(4, preview.receiving().after().eligibleStaff());
        DepartmentImpact donorImpact = preview.donors().get(0);
        assertEquals(4, donorImpact.before().eligibleStaff());
        assertEquals(3, donorImpact.after().eligibleStaff(), "the donor's capacity falls by the helper");

        service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        DepartmentCapacityDto.Entry after = entry(w.receiving);
        assertEquals(75.0, after.utilizationPercent(), "what the preview projected is what happened");
        assertEquals(WorkloadStatus.HIGH, after.level());
        assertFalse(after.atRisk());
    }

    @Test
    @DisplayName("A department below its threshold whose own staff cover its waiting work cannot use helpers")
    void notAtRiskAndCoveredNeedsNoHelpers() {
        World w = world(3, new int[]{100}, new int[]{50}, 4);                // 150 min on 3 people = 10.4%, 2 free people for 1 waiting task
        Options options = service.getOptions(w.receiving.getCode());
        assertEquals("NONE", options.receiving().needReason());
        assertEquals(0, options.maxHelpers());
        Preview preview = service.preview(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        assertFalse(preview.valid());
        assertTrue(preview.problems().stream().anyMatch(p -> "NOT_NEEDED".equals(p.code())), preview.problems().toString());
        assertTrue(recommendations.getRecommendations().recommendations().stream().noneMatch(r -> r.departmentCode().equals(w.receiving.getCode())));
    }

    @Test
    @DisplayName("A donor cannot be pushed past its limit: lending 2 of 4 people at 1000 min would put it at 104.2%, so only 1 may go; the plan is refused and nothing is created")
    void donorLimitBlocksThePlan() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 3);     // receiving: 150%, needs 2 helpers
        Staff busy = fx.eligible("D busy", w.donor);
        fx.assign(fx.task("Big donor job", w.donor, Priority.MEDIUM, 1000, TaskStatus.PENDING), busy);   // donor: 1000 min on 4 people = 52.1%
        assertEquals(52.1, entry(w.donor).utilizationPercent());

        Options options = service.getOptions(w.receiving.getCode());
        DonorDepartment donor = options.donors().stream().filter(x -> x.code().equals(w.donor.getCode())).findFirst().orElseThrow();
        assertEquals(1, donor.maxLendable(), "the reserve alone would allow 2 (3 free people), but its own load allows only 1");
        assertEquals(69.4, donor.utilizationAfterOne());

        Preview two = service.preview(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0), w.donors.get(1), w.waiting.get(1)));
        assertFalse(two.valid());
        SupportDtos.Problem overload = two.problems().stream().filter(p -> "DONOR_OVERLOAD".equals(p.code())).findFirst().orElse(null);
        assertNotNull(overload, two.problems().toString());
        assertTrue(overload.message().contains("104.2%"), overload.message());
        assertFalse(two.canOverrideReserve(), "this is not the reserve: nothing to acknowledge");

        SupportPlanRequest acknowledged = fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0), w.donors.get(1), w.waiting.get(1));
        acknowledged.setAcknowledgeReserveOverride(true);
        assertFalse(service.preview(acknowledged).valid(), "acknowledging the reserve cannot allow an overloaded donor");
        SupportPlanException refused = assertThrows(SupportPlanException.class, () -> service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0), w.donors.get(1), w.waiting.get(1))));
        assertEquals(409, refused.getStatus().value());
        assertTrue(refused.getProblems().stream().anyMatch(p -> "DONOR_OVERLOAD".equals(p.code())));
        assertEquals(0, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(x -> x.getReceivingDepartmentCode().equals(w.receiving.getCode())).count());
        assertEquals(2, entry(w.receiving).eligibleStaff(), "nothing moved");

        assertTrue(service.preview(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0))).valid(), "one helper is within the donor's limit");
    }

    @Test
    @DisplayName("With nobody able to lend safely, the department still needs support and the answer says so clearly")
    void noSafeHelperIsAClearAnswer() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 0);
        Staff busy = fx.eligible("D busy", w.donor);
        fx.assign(fx.task("Donor job", w.donor, Priority.MEDIUM, 400, TaskStatus.PENDING), busy);
        WorkloadRecommendationsDto result = recommendations.getRecommendations();
        assertTrue(result.recommendations().stream().noneMatch(r -> r.departmentCode().equals(w.receiving.getCode())));
        var unsupported = result.unsupported().stream().filter(u -> u.departmentCode().equals(w.receiving.getCode())).findFirst().orElse(null);
        assertNotNull(unsupported, "reported as needing support, not silently dropped");
        assertFalse(unsupported.reason().isBlank());
        assertTrue(unsupported.pressure().atRisk());
    }

    // ── settings and permissions ─────────────────────────────────────────────

    @Test
    @DisplayName("Switching Cross-Department Assistance off keeps existing support and its capacity, and refuses new plans")
    void assistanceOffKeepsExistingSupport() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        settings(true, false, 85);

        DepartmentCapacityDto.Entry r = entry(w.receiving);
        assertEquals(1, r.helpersIn(), "the helper stays with the receiving department");
        assertEquals(3, r.eligibleStaff());
        assertEquals(1, entry(w.donor).lentOut());
        assertEquals(1, supportRepository.findByStatus(SupportStatus.ACTIVE).stream().filter(x -> x.getReceivingDepartmentCode().equals(w.receiving.getCode())).count());

        SupportPlanException refused = assertThrows(SupportPlanException.class, () -> service.preview(fx.plan(w.receiving, w.donors.get(1), w.waiting.get(1))));
        assertEquals(409, refused.getStatus().value());
        assertTrue(recommendations.getRecommendations().recommendations().isEmpty(), "no new suggestions while it is off");
    }

    @Test
    @DisplayName("With the Max Task Capacity Alert off, utilization never triggers support or AT RISK, but the original uncovered-task rule still applies")
    void alertOffFallsBackToTheOriginalRule() {
        World covered = world(3, new int[]{480, 480}, new int[]{480}, 4);     // 100%, nothing uncovered
        settings(false, true, 85);
        assertFalse(entry(covered.receiving).atRisk());
        assertTrue(recommendations.getRecommendations().recommendations().stream().noneMatch(r -> r.departmentCode().equals(covered.receiving.getCode())));
        assertEquals("NONE", service.getOptions(covered.receiving.getCode()).receiving().needReason());

        World uncovered = world(1, new int[]{480}, new int[]{30, 30}, 4);     // 2 waiting tasks, nobody free: uncovered
        assertEquals("UNCOVERED_TASKS", service.getOptions(uncovered.receiving.getCode()).receiving().needReason());
        assertTrue(recommendations.getRecommendations().recommendations().stream().anyMatch(r -> r.departmentCode().equals(uncovered.receiving.getCode())));
    }

    @Test
    @DisplayName("A Manager sees the capacity of their own department only and cannot confirm; Staff are refused everything")
    void managerAndStaffPermissionsAreUnchanged() {
        World w = world(2, new int[]{480, 480}, new int[]{240, 240}, 4);
        scope.manager("cap_manager", w.receiving);
        SupportTestFixture.signInAs("cap_manager", "MANAGER");

        Set<String> codes = capacity.forCaller().departments().stream().map(DepartmentCapacityDto.Entry::code).collect(Collectors.toSet());
        assertEquals(Set.of(w.receiving.getCode()), codes, "only the department the Manager manages");
        ResponseStatusException cannotConfirm = assertThrows(ResponseStatusException.class, () -> service.confirm(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0))));
        assertEquals(403, cannotConfirm.getStatusCode().value());
        Preview managerPreview = service.preview(fx.plan(w.receiving, w.donors.get(0), w.waiting.get(0)));
        assertFalse(managerPreview.valid(), "a Manager can preview for their own department");
        assertEquals(150.0, managerPreview.receiving().before().utilizationPercent());
        assertEquals("DONOR_NOT_AUTHORIZED", managerPreview.problems().get(0).code(), "the donor's department is not one this Manager manages");
        assertTrue(managerPreview.donors().isEmpty(), "the donor's figures are not shown to a Manager who does not manage it");

        Staff plain = w.receivingStaff.get(0);
        SupportTestFixture.signInAs(plain.getUser().getUsername(), "STAFF");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> capacity.forCaller()).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.getOptions(w.receiving.getCode())).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> recommendations.getRecommendations()).getStatusCode().value());
    }
}
