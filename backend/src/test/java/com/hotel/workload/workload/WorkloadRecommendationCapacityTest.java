package com.hotel.workload.workload;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.WorkloadRecommendationEngine.CapacityInputs;
import com.hotel.workload.workload.WorkloadRecommendationsDto.DepartmentRecommendation;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Suggestion;
import com.hotel.workload.workload.WorkloadRecommendationsDto.UnsupportedDepartment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recommendation engine judging departments by capacity (see DepartmentCapacity): a department can need help because its utilization is at
 * or above the Capacity Threshold even when its own free staff could take every waiting task, and a donor is never pushed past its own limit.
 * Pure functions: the remaining minutes and the capacity staff of each department are passed in, so the numbers below are exact.
 *
 * <p>One eligible person is 480 minutes of capacity. Example used throughout: 1000 minutes on 2 people = 1000 / 960 = 104.2%; one helper makes
 * it 1000 / 1440 = 69.4%; the minutes themselves never change.
 */
class WorkloadRecommendationCapacityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);
    private static final LocalDateTime NOW = TODAY.atTime(9, 0);
    private static final DepartmentCapacity.Risk LIMIT_85 = DepartmentCapacity.Risk.of(true, 85);

    private final Department housekeeping = dept("HOUSEKEEPING", "Housekeeping");
    private final Department maintenance = dept("MAINTENANCE", "Maintenance");
    private final Department security = dept("SECURITY", "Security");

    private final List<Staff> staff = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();
    private final List<Assignment> assignments = new ArrayList<>();

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Department dept(String code, String name) {
        Department department = new Department(code, name);
        department.setId(UUID.randomUUID());
        department.setActive(true);
        return department;
    }

    private Staff person(String name, Department department, Role role, Availability availability, boolean active, boolean setupRequired) {
        Staff member = new Staff();
        member.setId(UUID.randomUUID());
        member.setName(name);
        member.setDepartment(department);
        member.setAvailability(availability);
        member.setActive(active);
        User account = new User();
        account.setUsername(name);
        account.setRole(role);
        account.setActive(true);
        account.setEmailVerified(true);
        account.setCredentialsSetupRequired(setupRequired);
        member.setUser(account);
        staff.add(member);
        return member;
    }

    private Staff free(String name, Department department) {
        return person(name, department, Role.STAFF, Availability.AVAILABLE, true, false);
    }

    private Task waiting(String title, Department department, int minutes) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setDepartment(department);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(minutes);
        tasks.add(task);
        return task;
    }

    private static CapacityInputs capacity(DepartmentCapacity.Risk risk, int hkMinutes, int hkStaff, int mtMinutes, int mtStaff, int secMinutes, int secStaff) {
        return CapacityInputs.of(
                Map.of("HOUSEKEEPING", hkMinutes, "MAINTENANCE", mtMinutes, "SECURITY", secMinutes),
                Map.of("HOUSEKEEPING", hkStaff, "MAINTENANCE", mtStaff, "SECURITY", secStaff), risk);
    }

    private WorkloadRecommendationsDto run(CapacityInputs capacity) {
        return WorkloadRecommendationEngine.recommend(List.of(housekeeping, maintenance, security), staff, tasks, assignments, TODAY, NOW, capacity);
    }

    private static DepartmentRecommendation recommendationFor(WorkloadRecommendationsDto dto, String code) {
        return dto.recommendations().stream().filter(r -> r.departmentCode().equals(code)).findFirst().orElse(null);
    }

    private static UnsupportedDepartment unsupportedFor(WorkloadRecommendationsDto dto, String code) {
        return dto.unsupported().stream().filter(r -> r.departmentCode().equals(code)).findFirst().orElse(null);
    }

    /** Housekeeping: two free people who could take its two waiting tasks, so by the original rule it needs nothing. */
    private void housekeepingCoveredByItsOwnStaff() {
        free("Hana", housekeeping);
        free("Hugo", housekeeping);
        waiting("Deep clean suite 9", housekeeping, 60);
        waiting("Restock linen", housekeeping, 60);
    }

    private void securityWithFreeStaff(int people) {
        for (int i = 1; i <= people; i++) free("Sam" + i, security);
    }

    // ── what triggers support ────────────────────────────────────────────────

    @Test
    @DisplayName("A department at or above the threshold needs support even though its own free staff could take every waiting task")
    void riskAloneTriggersSupport() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(4);
        // Housekeeping 1000 min on 2 people = 104.2%; Security 200 min on 4 people = 10.4%
        WorkloadRecommendationsDto result = run(capacity(LIMIT_85, 1000, 2, 0, 0, 200, 4));

        DepartmentRecommendation housekeepingNeed = recommendationFor(result, "HOUSEKEEPING");
        assertNotNull(housekeepingNeed, "needs support because of its utilization");
        assertEquals("HIGH_UTILIZATION", housekeepingNeed.pressure().needReason());
        assertEquals(0, housekeepingNeed.pressure().uncoveredTasks(), "by the original rule it would not need anything");
        assertTrue(housekeepingNeed.pressure().atRisk());
        assertEquals(104.2, housekeepingNeed.pressure().utilizationPercent());
        assertEquals(1000, housekeepingNeed.pressure().remainingMinutes());
        assertEquals(WorkloadStatus.AT_RISK, housekeepingNeed.pressure().level());
    }

    @Test
    @DisplayName("Without capacity inputs, or with the alert switched off, only the original uncovered-task rule applies")
    void withoutCapacityOrWithAlertOffNothingChanges() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(4);
        assertTrue(run(CapacityInputs.NONE).recommendations().isEmpty(), "no capacity model: unchanged behaviour");
        assertTrue(run(capacity(DepartmentCapacity.Risk.of(false, 85), 1000, 2, 0, 0, 200, 4)).recommendations().isEmpty(), "alert off: utilization does not trigger anything");
        assertTrue(run(capacity(DepartmentCapacity.Risk.of(false, 85), 1000, 2, 0, 0, 200, 4)).unsupported().isEmpty());
    }

    @Test
    @DisplayName("Just below the threshold nothing is triggered, exactly at it something is: 816 of 960 minutes is 85.0%")
    void exactlyAtTheThresholdTriggers() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(4);
        assertTrue(run(capacity(LIMIT_85, 815, 2, 0, 0, 200, 4)).recommendations().isEmpty(), "815 / 960 = 84.9%: below");
        assertNotNull(recommendationFor(run(capacity(LIMIT_85, 816, 2, 0, 0, 200, 4)), "HOUSEKEEPING"), "816 / 960 = 85.0%: at the limit");
    }

    // ── what helpers do to the numbers ───────────────────────────────────────

    @Test
    @DisplayName("One helper lowers the receiver from 104.2% to 69.4% and raises the donor from 10.4% to 13.9%, while the minutes stay 1000 and 200")
    void oneHelperMovesCapacityNotWork() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(4);
        DepartmentRecommendation need = recommendationFor(run(capacity(LIMIT_85, 1000, 2, 0, 0, 200, 4)), "HOUSEKEEPING");

        assertEquals(1, need.suggestions().size(), "one extra person is enough to get below 85%");
        Suggestion helper = need.suggestions().get(0);
        assertEquals("SECURITY", helper.homeDepartmentCode());
        assertEquals(104.2, helper.receiverImpact().utilizationBefore());
        assertEquals(69.4, helper.receiverImpact().utilizationAfter());
        assertEquals(Boolean.FALSE, helper.receiverImpact().stillAtRiskAfter(), "the projection confirms the risk is gone");
        assertEquals(10.4, helper.donorImpact().utilizationBefore());
        assertEquals(13.9, helper.donorImpact().utilizationAfter());
        assertEquals(1000, need.pressure().remainingMinutes(), "the department's own minutes are not reduced by a helper joining");
        assertTrue(helper.reasons().stream().anyMatch(r -> r.contains("104.2%") && r.contains("69.4%")), "the reasons say what changes: " + helper.reasons());
        assertTrue(helper.reasons().stream().anyMatch(r -> r.contains("Security stays at 13.9%")), "and what it costs the donor: " + helper.reasons());
    }

    @Test
    @DisplayName("Several helpers give the projected utilization after each: 2000 min on 2 people goes 208.3 -> 138.9 -> 104.2 -> 83.3")
    void severalHelpersProjectEachStep() {
        free("Hana", housekeeping);
        free("Hugo", housekeeping);
        for (int i = 1; i <= 4; i++) waiting("Job " + i, housekeeping, 50);
        securityWithFreeStaff(6);
        DepartmentRecommendation need = recommendationFor(run(capacity(LIMIT_85, 2000, 2, 0, 0, 300, 6)), "HOUSEKEEPING");

        assertEquals(3, need.suggestions().size(), "three people are needed to get below 85% (5 x 480 = 2400: 83.3%)");
        double[][] steps = {{208.3, 138.9}, {138.9, 104.2}, {104.2, 83.3}};
        for (int i = 0; i < 3; i++) {
            assertEquals(steps[i][0], need.suggestions().get(i).receiverImpact().utilizationBefore(), "step " + (i + 1) + " before");
            assertEquals(steps[i][1], need.suggestions().get(i).receiverImpact().utilizationAfter(), "step " + (i + 1) + " after");
        }
        assertEquals(Boolean.TRUE, need.suggestions().get(1).receiverImpact().stillAtRiskAfter(), "104.2% is still at risk");
        assertEquals(Boolean.FALSE, need.suggestions().get(2).receiverImpact().stillAtRiskAfter(), "83.3% is not");
        Set<UUID> ids = new HashSet<>();
        for (Suggestion s : need.suggestions()) assertTrue(ids.add(s.staffId()), "nobody is suggested twice");
    }

    @Test
    @DisplayName("Every helper takes a different waiting task, most important first, and never more helpers than waiting tasks")
    void helpersTakeDistinctWaitingTasks() {
        free("Hana", housekeeping);
        free("Hugo", housekeeping);
        waiting("Only job", housekeeping, 60);
        securityWithFreeStaff(6);
        DepartmentRecommendation need = recommendationFor(run(capacity(LIMIT_85, 2000, 2, 0, 0, 300, 6)), "HOUSEKEEPING");
        assertEquals(1, need.suggestions().size(), "one waiting task, so at most one helper (a helper always takes a waiting task)");
        assertEquals("Only job", need.suggestions().get(0).coversTaskTitle());
    }

    // ── donor protection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A donor is never pushed to or past its limit: lending one of 3 people at 816 min would put it at 85.0%, so it is refused; at 815 it may")
    void donorCannotBePushedPastItsLimit() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(3);
        // Security with 3 people: 816 / (2 x 480) = 85.0% after lending one -> at the limit -> refused
        WorkloadRecommendationsDto refused = run(capacity(LIMIT_85, 1000, 2, 0, 0, 816, 3));
        assertNull(recommendationFor(refused, "HOUSEKEEPING"), "the only donor would itself be at its limit");
        UnsupportedDepartment unsupported = unsupportedFor(refused, "HOUSEKEEPING");
        assertNotNull(unsupported, "the department still needs help, and the answer says no safe helper exists");
        assertTrue(unsupported.reason().startsWith("No suitable support is available"), unsupported.reason());

        WorkloadRecommendationsDto allowed = run(capacity(LIMIT_85, 1000, 2, 0, 0, 815, 3));
        assertNotNull(recommendationFor(allowed, "HOUSEKEEPING"), "815 / 960 = 84.9% after lending: allowed");
        assertEquals(84.9, recommendationFor(allowed, "HOUSEKEEPING").suggestions().get(0).donorImpact().utilizationAfter());
    }

    @Test
    @DisplayName("A donor that is itself at or above the limit never lends, and a donor with work but only one person cannot be left with nobody")
    void atRiskOrSingleDonorsNeverLend() {
        housekeepingCoveredByItsOwnStaff();
        securityWithFreeStaff(2);
        assertNull(recommendationFor(run(capacity(LIMIT_85, 1000, 2, 0, 0, 900, 2)), "HOUSEKEEPING"), "Security is at 93.8% itself");
        // reserve rule on top: with two free people and no waiting tasks it may lend one; with capacity 1 person and 100 min it would be left with nobody to do it
        staff.removeIf(s -> s.getDepartment() == security);
        securityWithFreeStaff(1);
        assertNull(recommendationFor(run(capacity(LIMIT_85, 1000, 2, 0, 0, 100, 1)), "HOUSEKEEPING"), "its only person stays in reserve and could not carry its own 100 min alone");
    }

    @Test
    @DisplayName("Capacity left by one recommendation is respected by the next: a donor that can safely lend only one person lends it once")
    void donorCapacityIsSharedAcrossReceivers() {
        housekeepingCoveredByItsOwnStaff();
        free("Mia", maintenance);
        free("Max", maintenance);
        waiting("Fix lift", maintenance, 60);
        waiting("Fix door", maintenance, 60);
        securityWithFreeStaff(3);
        // Housekeeping 1000/2 people = 104.2%, Maintenance 1000/2 = 104.2%; Security 600 min on 3 people: 62.5% after one loan, 125% after two
        WorkloadRecommendationsDto result = run(capacity(LIMIT_85, 1000, 2, 900, 2, 600, 3));

        long helpers = result.recommendations().stream().mapToLong(r -> r.suggestions().size()).sum();
        assertEquals(1, helpers, "Security can safely lend exactly one person across all receivers");
        assertEquals(1, result.recommendations().size());
        assertEquals(1, result.unsupported().size(), "the other department still needs help and is told no safe helper exists");
    }

    // ── who can be recommended ───────────────────────────────────────────────

    @Test
    @DisplayName("An unavailable, inactive, Manager or not-yet-set-up person is never recommended, so nobody safe means a clear no-helper answer")
    void ineligibleStaffAreNeverRecommended() {
        housekeepingCoveredByItsOwnStaff();
        person("Away", security, Role.STAFF, Availability.UNAVAILABLE, true, false);
        person("Inactive", security, Role.STAFF, Availability.AVAILABLE, false, false);
        person("Boss", security, Role.MANAGER, Availability.AVAILABLE, true, false);
        person("New", security, Role.STAFF, Availability.AVAILABLE, true, true);
        WorkloadRecommendationsDto result = run(capacity(LIMIT_85, 1000, 2, 0, 0, 100, 5));
        assertNull(recommendationFor(result, "HOUSEKEEPING"));
        assertNotNull(unsupportedFor(result, "HOUSEKEEPING"), "clear no-safe-helper result");
        assertFalse(unsupportedFor(result, "HOUSEKEEPING").reason().isBlank());
    }

    @Test
    @DisplayName("A department at risk with no task to take or join is reported, with the reason a helper cannot be sent")
    void atRiskWithNothingWaitingExplainsWhy() {
        free("Hana", housekeeping);
        free("Hugo", housekeeping);
        securityWithFreeStaff(4);
        WorkloadRecommendationsDto result = run(capacity(LIMIT_85, 1000, 2, 0, 0, 100, 4));
        UnsupportedDepartment unsupported = unsupportedFor(result, "HOUSEKEEPING");
        assertNotNull(unsupported);
        assertTrue(unsupported.reason().contains("no active task a helper could join"), unsupported.reason());
        assertTrue(unsupported.pressure().atRisk());
        assertEquals("HIGH_UTILIZATION", unsupported.pressure().needReason());
    }

    @Test
    @DisplayName("Work with nobody eligible to do it has no capacity: it needs support whatever the threshold")
    void noCapacityNeedsSupport() {
        waiting("Orphan task", housekeeping, 60);
        securityWithFreeStaff(4);
        DepartmentRecommendation need = recommendationFor(run(capacity(LIMIT_85, 60, 0, 0, 0, 100, 4)), "HOUSEKEEPING");
        assertNotNull(need);
        assertTrue(need.pressure().noCapacity());
        assertNull(need.pressure().utilizationPercent(), "no percentage without capacity");
        assertEquals(1, need.suggestions().size());
        assertTrue(need.reasons().stream().anyMatch(r -> r.contains("no eligible staff")), "says why: " + need.reasons());
    }
}
