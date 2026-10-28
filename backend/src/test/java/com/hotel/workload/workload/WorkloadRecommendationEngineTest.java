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
import com.hotel.workload.workload.WorkloadRecommendationsDto.DepartmentRecommendation;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Severity;
import com.hotel.workload.workload.WorkloadRecommendationsDto.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadRecommendationEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);
    private static final LocalDateTime NOW = TODAY.atTime(9, 0);

    private final Department housekeeping = dept("HOUSEKEEPING", "Housekeeping", true);
    private final Department maintenance = dept("MAINTENANCE", "Maintenance", true);
    private final Department security = dept("SECURITY", "Security", true);

    private final List<Staff> staff = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();
    private final List<Assignment> assignments = new ArrayList<>();

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Department dept(String code, String name, boolean active) {
        Department department = new Department(code, name);
        department.setId(UUID.randomUUID());
        department.setActive(active);
        return department;
    }

    private Staff person(String name, Department department, Availability availability, boolean active) {
        Staff member = new Staff();
        member.setId(UUID.randomUUID());
        member.setName(name);
        member.setDepartment(department);
        member.setAvailability(availability);
        member.setActive(active);
        member.setUser(account(name, Role.STAFF, true, false, true));
        staff.add(member);
        return member;
    }

    /** A login account: by default what a normal, fully set up STAFF account looks like. */
    private static User account(String name, Role role, boolean emailVerified, boolean setupRequired, boolean active) {
        User account = new User();
        account.setUsername(name);
        account.setRole(role);
        account.setActive(active);
        account.setEmailVerified(emailVerified);
        account.setCredentialsSetupRequired(setupRequired);
        return account;
    }

    /** A free, available staff record whose account has the given role and sign-in state. */
    private Staff freePersonWithAccount(String name, Department department, Role role, boolean emailVerified, boolean setupRequired, boolean accountActive) {
        Staff member = freePerson(name, department);
        member.setUser(account(name, role, emailVerified, setupRequired, accountActive));
        return member;
    }

    private Staff freePerson(String name, Department department) {
        return person(name, department, Availability.AVAILABLE, true);
    }

    private Task task(String title, Department department, Priority priority, Integer minutes, LocalDate due) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setDepartment(department);
        task.setPriority(priority);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(minutes);
        task.setDueDate(due);
        tasks.add(task);
        return task;
    }

    /** Gives the person an active assignment (so they are busy) and returns the assigned task. */
    private Task busy(Staff member, Department department) {
        Task task = task("Assigned for " + member.getName(), department, Priority.MEDIUM, 60, null);
        task.setStatus(TaskStatus.ASSIGNED);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(member);
        assignments.add(assignment);
        return task;
    }

    private WorkloadRecommendationsDto run() {
        return WorkloadRecommendationEngine.recommend(List.of(housekeeping, maintenance, security), staff, tasks, assignments, TODAY, NOW);
    }

    private DepartmentRecommendation forDept(WorkloadRecommendationsDto dto, String code) {
        return dto.recommendations().stream().filter(r -> r.departmentCode().equals(code)).findFirst().orElse(null);
    }

    /** Housekeeping: one busy person and two tasks nobody has taken. */
    private void overloadedHousekeeping() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        task("Deep clean suite 9", housekeeping, Priority.URGENT, 90, null);
        task("Restock linen", housekeeping, Priority.MEDIUM, 30, null);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Recommends free staff from a department with spare people for one with uncovered waiting work")
    void recommendsSupportForOverloadedDepartment() {
        overloadedHousekeeping();
        Staff m1 = freePerson("Mia", maintenance);
        Staff m2 = freePerson("Max", maintenance);
        Staff m3 = freePerson("Moe", maintenance);

        WorkloadRecommendationsDto dto = run();

        DepartmentRecommendation rec = forDept(dto, "HOUSEKEEPING");
        assertNotNull(rec);
        assertEquals(Severity.CRITICAL, rec.severity(), "an urgent task is waiting");
        assertEquals(2, rec.pressure().waitingTasks());
        assertEquals(120, rec.pressure().waitingMinutes());
        assertEquals(0, rec.pressure().freeStaff());
        assertEquals(2, rec.pressure().uncoveredTasks());
        assertEquals(2, rec.suggestions().size());
        Set<UUID> allowed = Set.of(m1.getId(), m2.getId(), m3.getId());
        assertTrue(rec.suggestions().stream().allMatch(s -> allowed.contains(s.staffId()) && s.homeDepartmentCode().equals("MAINTENANCE")));

        Suggestion first = rec.suggestions().get(0);
        assertEquals("Deep clean suite 9", first.coversTaskTitle(), "the most important uncovered task comes first");
        assertEquals(3, first.donorImpact().freeStaffBefore());
        assertEquals(2, first.donorImpact().freeStaffAfter());
        assertEquals(2, first.receiverImpact().uncoveredTasksBefore());
        assertEquals(1, first.receiverImpact().uncoveredTasksAfter());
        assertEquals(120, first.receiverImpact().uncoveredMinutesBefore());
        assertEquals(30, first.receiverImpact().uncoveredMinutesAfter());
        assertFalse(first.reasons().isEmpty());
        assertTrue(rec.reasons().get(0).contains("2 tasks waiting"));
    }

    @Test
    @DisplayName("Never suggests inactive, unavailable or already busy staff")
    void neverSuggestsIneligibleStaff() {
        overloadedHousekeeping();
        person("Inactive Ian", maintenance, Availability.AVAILABLE, false);
        person("Away Amy", maintenance, Availability.UNAVAILABLE, true);
        busy(freePerson("Busy Bo", maintenance), maintenance);
        Staff eligible = freePerson("Eli", maintenance);
        freePerson("Eve", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        Set<String> names = rec.suggestions().stream().map(Suggestion::staffName).collect(Collectors.toSet());
        assertFalse(names.contains("Inactive Ian"));
        assertFalse(names.contains("Away Amy"));
        assertFalse(names.contains("Busy Bo"));
        assertTrue(names.contains("Eli") || names.contains("Eve"));
        assertNotNull(eligible);
    }

    @Test
    @DisplayName("Does not lend staff when that would leave the lending department with uncovered work of its own")
    void doesNotWorsenDonorBacklog() {
        overloadedHousekeeping();
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);
        task("Fix lift", maintenance, Priority.URGENT, 60, null);
        task("Repaint hall", maintenance, Priority.LOW, 120, null);

        WorkloadRecommendationsDto dto = run();

        assertNull(forDept(dto, "HOUSEKEEPING"));
        assertEquals(1, dto.unsupported().size());
        assertEquals("HOUSEKEEPING", dto.unsupported().get(0).departmentCode());
    }

    @Test
    @DisplayName("Never takes a department's last available person")
    void keepsAtLeastOneAvailablePersonInTheDonor() {
        overloadedHousekeeping();
        freePerson("Only Olga", maintenance);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertEquals(1, dto.unsupported().size());
    }

    @Test
    @DisplayName("A department whose own free staff can take everything waiting is not under pressure")
    void noPressureWhenOwnStaffCanCover() {
        freePerson("Hana", housekeeping);
        freePerson("Hugo", housekeeping);
        task("Restock linen", housekeeping, Priority.URGENT, 30, null);
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertTrue(dto.unsupported().isEmpty());
    }

    @Test
    @DisplayName("A department with no waiting work is never recommended, however low its workload")
    void quietDepartmentsProduceNothing() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertTrue(dto.unsupported().isEmpty());
    }

    @Test
    @DisplayName("Inactive departments neither ask for nor give support")
    void ignoresInactiveDepartments() {
        Department closed = dept("CLOSED", "Closed", false);
        freePerson("Cleo", closed);
        freePerson("Carl", closed);
        freePerson("Cara", closed);
        task("Stuck task", closed, Priority.URGENT, 10, null);
        overloadedHousekeeping();

        WorkloadRecommendationsDto dto = WorkloadRecommendationEngine.recommend(
                List.of(housekeeping, maintenance, closed), staff, tasks, assignments, TODAY, NOW);

        assertTrue(dto.recommendations().stream().noneMatch(r -> r.departmentCode().equals("CLOSED")));
        assertTrue(dto.unsupported().stream().anyMatch(u -> u.departmentCode().equals("HOUSEKEEPING")), "closed department staff are not lent");
    }

    @Test
    @DisplayName("Nobody is suggested to two departments, and a single department gets at most three people")
    void eachPersonOnceAndSuggestionsAreCapped() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        for (int i = 0; i < 5; i++) task("Waiting " + i, housekeeping, Priority.MEDIUM, 20, null);
        busy(freePerson("Sam", security), security);
        task("Gate check", security, Priority.HIGH, 20, null);
        for (int i = 0; i < 6; i++) freePerson("Maint " + i, maintenance);

        WorkloadRecommendationsDto dto = run();

        List<UUID> ids = dto.recommendations().stream().flatMap(r -> r.suggestions().stream()).map(Suggestion::staffId).toList();
        assertEquals(ids.size(), Set.copyOf(ids).size(), "no one appears twice");
        assertEquals(WorkloadRecommendationEngine.MAX_SUGGESTIONS_PER_DEPARTMENT, forDept(dto, "HOUSEKEEPING").suggestions().size());
        assertEquals(1, forDept(dto, "SECURITY").suggestions().size());
    }

    @Test
    @DisplayName("Severity follows priority and deadlines of the uncovered work")
    void severityReflectsPriorityAndDueDates() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        task("Overdue towels", housekeeping, Priority.LOW, 15, TODAY.minusDays(1));
        busy(freePerson("Sam", security), security);
        task("Tomorrow patrol", security, Priority.LOW, 15, TODAY.plusDays(1));
        busy(freePerson("Mel", maintenance), maintenance);
        task("Someday", maintenance, Priority.LOW, 15, TODAY.plusDays(30));
        Department a = dept("ALPHA", "Alpha", true);
        for (int i = 0; i < 4; i++) freePerson("Alpha " + i, a);

        WorkloadRecommendationsDto dto = WorkloadRecommendationEngine.recommend(
                List.of(housekeeping, maintenance, security, a), staff, tasks, assignments, TODAY, NOW);

        assertEquals(Severity.CRITICAL, forDept(dto, "HOUSEKEEPING").severity());
        assertEquals(Severity.HIGH, forDept(dto, "SECURITY").severity());
        assertEquals(Severity.MODERATE, forDept(dto, "MAINTENANCE").severity());
        assertEquals("HOUSEKEEPING", dto.recommendations().get(0).departmentCode(), "most severe first");
    }

    @Test
    @DisplayName("Tasks with no estimated duration count as zero minutes and are reported")
    void reportsMissingDurations() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        task("No duration", housekeeping, Priority.MEDIUM, null, null);
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(0, rec.pressure().waitingMinutes());
        assertEquals(1, rec.pressure().tasksWithoutDuration());
        assertTrue(rec.reasons().stream().anyMatch(r -> r.contains("no estimated duration")));
    }

    @Test
    @DisplayName("States what the data cannot tell, and moves nobody")
    void statesLimitationsAndLeavesDataUntouched() {
        overloadedHousekeeping();
        Staff mia = freePerson("Mia", maintenance);
        freePerson("Max", maintenance);
        freePerson("Moe", maintenance);
        int tasksBefore = tasks.size();
        int assignmentsBefore = assignments.size();

        WorkloadRecommendationsDto dto = run();

        String limitations = String.join(" ", dto.limitations()).toLowerCase();
        assertTrue(limitations.contains("skills") && limitations.contains("shifts") && limitations.contains("own department"));
        assertSame(maintenance, mia.getDepartment(), "permanent department is untouched");
        assertEquals(tasksBefore, tasks.size());
        assertEquals(assignmentsBefore, assignments.size());
        assertEquals(TaskStatus.PENDING, tasks.get(1).getStatus());
        assertEquals(NOW, dto.generatedAt());
    }

    @Test
    @DisplayName("Active tasks without a department are reported, not guessed")
    void reportsTasksWithoutDepartment() {
        task("Nowhere", null, Priority.HIGH, 10, null);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.limitations().stream().anyMatch(l -> l.contains("no department")));
        assertTrue(dto.recommendations().isEmpty());
    }

    @Test
    @DisplayName("Without any data there is nothing to recommend")
    void emptyData() {
        WorkloadRecommendationsDto dto = run();
        assertTrue(dto.recommendations().isEmpty());
        assertTrue(dto.unsupported().isEmpty());
        assertFalse(dto.limitations().isEmpty());
    }

    @Test
    @DisplayName("Realistic hotel: Housekeeping is overloaded, Maintenance has spare people, others are left alone")
    void realisticOverloadedHousekeepingScenario() {
        // Housekeeping: 4 people. Two are busy, one is off sick (Unavailable), one left the hotel (inactive).
        Staff hanaBusy = freePerson("Hana", housekeeping);
        Staff hugoBusy = freePerson("Hugo", housekeeping);
        person("Hope (off sick)", housekeeping, Availability.UNAVAILABLE, true);
        person("Hal (left)", housekeeping, Availability.AVAILABLE, false);
        busy(hanaBusy, housekeeping);
        busy(hugoBusy, housekeeping);
        // Five rooms waiting: two urgent (one overdue), one high, two routine
        task("VIP suite turnover", housekeeping, Priority.URGENT, 60, TODAY.minusDays(1));
        task("Conference room reset", housekeeping, Priority.URGENT, 45, TODAY);
        task("Pool towels", housekeeping, Priority.HIGH, 30, TODAY.plusDays(1));
        task("Linen audit", housekeeping, Priority.MEDIUM, 40, null);
        task("Stock minibars", housekeeping, Priority.LOW, 25, null);

        // Maintenance: 5 people. One is mid-repair, one is on leave, three are free; nothing waiting.
        busy(freePerson("Mel (mid-repair)", maintenance), maintenance);
        person("Max (leave)", maintenance, Availability.UNAVAILABLE, true);
        Staff nia = freePerson("Nia", maintenance);
        Staff omar = freePerson("Omar", maintenance);
        Staff pia = freePerson("Pia", maintenance);

        // Security is fully occupied but has nothing waiting: no pressure, and no spare staff to lend
        busy(freePerson("Sam", security), security);
        busy(freePerson("Sue", security), security);

        WorkloadRecommendationsDto dto = run();

        assertEquals(1, dto.recommendations().size(), "only Housekeeping needs support");
        DepartmentRecommendation rec = forDept(dto, "HOUSEKEEPING");
        assertEquals(Severity.CRITICAL, rec.severity());
        assertEquals(5, rec.pressure().waitingTasks());
        assertEquals(3, rec.pressure().urgentOrHighTasks(), "two urgent and one high");
        assertEquals(0, rec.pressure().freeStaff());
        assertEquals(5, rec.pressure().uncoveredTasks());

        // Maintenance has 3 free people, so it can lend 2 and must keep 1 free in reserve (Pia)
        assertEquals(2, rec.suggestions().size());
        assertEquals(Set.of(nia.getId(), omar.getId()), rec.suggestions().stream().map(Suggestion::staffId).collect(Collectors.toSet()));
        assertFalse(rec.suggestions().stream().anyMatch(s -> s.staffId().equals(pia.getId())), "the last free person stays in Maintenance");
        assertTrue(rec.suggestions().stream().allMatch(s -> s.homeDepartmentCode().equals("MAINTENANCE")));
        assertEquals(List.of("VIP suite turnover", "Conference room reset"),
                rec.suggestions().stream().map(Suggestion::coversTaskTitle).toList(), "most urgent work first");
        Suggestion last = rec.suggestions().get(1);
        assertEquals(2, last.donorImpact().freeStaffBefore());
        assertEquals(1, last.donorImpact().freeStaffAfter(), "at least one free Maintenance person always remains");
        assertEquals(3, last.donorImpact().availableStaffBefore());
        assertEquals(2, last.donorImpact().availableStaffAfter());
        assertEquals(4, last.receiverImpact().uncoveredTasksBefore());
        assertEquals(3, last.receiverImpact().uncoveredTasksAfter(), "three Housekeeping tasks stay uncovered after two helpers");

        // Permanent departments are unchanged by producing the recommendation
        assertTrue(rec.suggestions().stream().allMatch(s -> staff.stream()
                .filter(m -> m.getId().equals(s.staffId())).findFirst().orElseThrow().getDepartment() == maintenance));
        assertTrue(dto.unsupported().isEmpty());
    }

    // ── reserve rule: a lending department keeps at least one free, available person ──────────────────────────

    @Test
    @DisplayName("A department with exactly one free person is never a donor, even with nothing waiting in it")
    void departmentWithOneFreePersonIsNotADonor() {
        overloadedHousekeeping();
        Staff onlyFree = freePerson("Fran", security);
        busy(freePerson("Sid", security), security);   // available but busy: not free

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty(), "Security would be left with no free person");
        assertEquals(1, dto.unsupported().size());
        assertNotNull(onlyFree);
        assertTrue(dto.unsupported().get(0).reason().startsWith("No suitable support is available"));
        assertTrue(dto.unsupported().get(0).reason().contains("without a free, available staff member"));
    }

    @Test
    @DisplayName("A department with two free people can lend exactly one and keeps the other")
    void departmentWithTwoFreePeopleLendsOneAndKeepsOne() {
        busy(freePerson("Hana", housekeeping), housekeeping);
        task("Urgent turnover", housekeeping, Priority.URGENT, 30, null);
        task("Second room", housekeeping, Priority.HIGH, 30, null);   // two tasks are uncovered, but only one helper is allowed
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(1, rec.suggestions().size(), "the second Maintenance person is the reserve");
        assertEquals("Max", rec.suggestions().get(0).staffName());
        assertEquals(2, rec.suggestions().get(0).donorImpact().freeStaffBefore());
        assertEquals(1, rec.suggestions().get(0).donorImpact().freeStaffAfter());
        assertEquals(2, rec.pressure().uncoveredTasks());
        assertEquals(1, rec.suggestions().get(0).receiverImpact().uncoveredTasksAfter() , "one task is still uncovered afterwards");
    }

    @Test
    @DisplayName("The reserve is kept in addition to covering the donor's own waiting tasks")
    void reserveAndOwnBacklogBothApply() {
        overloadedHousekeeping();
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);
        freePerson("Moe", maintenance);
        task("Fix lift", maintenance, Priority.HIGH, 30, null);   // one waiting task: needs one free person for itself

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        // 3 free, 1 waiting: lending 1 leaves 2 (covers the waiting task and a reserve); lending a 2nd would leave 1,
        // which still covers the waiting task and is the reserve, so it is allowed; a 3rd would leave none.
        assertEquals(2, rec.suggestions().size());
        assertEquals(1, rec.suggestions().get(1).donorImpact().freeStaffAfter());
        assertEquals(1, rec.suggestions().get(1).donorImpact().waitingTasks());
    }

    @Test
    @DisplayName("An overloaded department still gets suggestions from every eligible donor, and nobody is lent twice")
    void overloadedDepartmentUsesAllEligibleDonors() {
        overloadedHousekeeping();   // 2 uncovered tasks
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);          // Maintenance: 2 free -> can lend 1
        freePerson("Sven", security);
        freePerson("Sofia", security);           // Security: 2 free -> can lend 1

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(2, rec.suggestions().size());
        assertEquals(Set.of("MAINTENANCE", "SECURITY"), rec.suggestions().stream().map(Suggestion::homeDepartmentCode).collect(Collectors.toSet()));
        assertEquals(2, rec.suggestions().stream().map(Suggestion::staffId).distinct().count());
        assertTrue(rec.suggestions().stream().allMatch(s -> s.donorImpact().freeStaffAfter() >= 1), "every donor keeps a free person");
    }

    @Test
    @DisplayName("When no department can lend anyone the result says no suitable support is available")
    void explainsWhenNoSuitableSupportExists() {
        overloadedHousekeeping();
        freePerson("Mia", maintenance);      // one free person each: neither may lend
        freePerson("Sven", security);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertEquals(1, dto.unsupported().size());
        assertEquals("HOUSEKEEPING", dto.unsupported().get(0).departmentCode());
        assertTrue(dto.unsupported().get(0).reason().contains("No suitable support is available"));
        assertEquals(2, dto.unsupported().get(0).pressure().uncoveredTasks());
    }

    @Test
    @DisplayName("Suggestion reasons describe the reserve and the remaining free count")
    void reasonsMentionTheReserve() {
        overloadedHousekeeping();
        freePerson("Mia", maintenance);
        freePerson("Max", maintenance);

        Suggestion suggestion = forDept(run(), "HOUSEKEEPING").suggestions().get(0);

        String reason = suggestion.reasons().get(0);
        assertTrue(reason.contains("has 2 available staff with no active task"), reason);
        assertTrue(reason.contains("lending one leaves 1 free"), reason);
        assertTrue(reason.contains("keeps a free person in reserve"), reason);
    }

    // ── who may be suggested: active STAFF accounts that can sign in ──────────────────────────────────────────

    @Test
    @DisplayName("Managers and admins are never suggested, even with a staff record, but they still count as free staff of their department")
    void managersAndAdminsAreNeverSuggested() {
        overloadedHousekeeping();
        freePersonWithAccount("Maya Manager", maintenance, Role.MANAGER, true, false, true);
        freePersonWithAccount("Adam Admin", maintenance, Role.ADMIN, true, false, true);
        freePerson("Nia", maintenance);
        freePerson("Omar", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(List.of("Nia", "Omar"), rec.suggestions().stream().map(Suggestion::staffName).toList());
        // 4 free people (2 of them not suggestable): lending Nia leaves 3 free, then Omar leaves 2
        assertEquals(4, rec.suggestions().get(0).donorImpact().freeStaffBefore());
        assertEquals(2, rec.suggestions().get(1).donorImpact().freeStaffAfter());
    }

    @Test
    @DisplayName("A department whose free people are all managers or admins has nobody to lend")
    void departmentOfOnlyManagersIsNotADonor() {
        overloadedHousekeeping();
        freePersonWithAccount("Mia Manager", maintenance, Role.MANAGER, true, false, true);
        freePersonWithAccount("Max Manager", maintenance, Role.MANAGER, true, false, true);
        freePersonWithAccount("Ada Admin", maintenance, Role.ADMIN, true, false, true);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertEquals(1, dto.unsupported().size());
        assertTrue(dto.unsupported().get(0).reason().contains("no free STAFF member who can sign in"));
    }

    @Test
    @DisplayName("Staff who have not verified their email cannot sign in, so they are never suggested")
    void unverifiedStaffAreNeverSuggested() {
        overloadedHousekeeping();
        freePersonWithAccount("Una Unverified", maintenance, Role.STAFF, false, true, true);
        freePersonWithAccount("Uri Unverified", maintenance, Role.STAFF, false, true, true);
        freePerson("Nia", maintenance);
        freePerson("Omar", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(List.of("Nia", "Omar"), rec.suggestions().stream().map(Suggestion::staffName).toList());
    }

    @Test
    @DisplayName("Staff with a verified email but credentials setup still pending cannot sign in either")
    void staffWithCredentialsSetupPendingAreNeverSuggested() {
        overloadedHousekeeping();
        freePersonWithAccount("Sam Setup", maintenance, Role.STAFF, true, true, true);
        freePersonWithAccount("Sia Setup", maintenance, Role.STAFF, true, true, true);
        freePerson("Nia", maintenance);
        freePerson("Omar", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(List.of("Nia", "Omar"), rec.suggestions().stream().map(Suggestion::staffName).toList());
    }

    @Test
    @DisplayName("A staff record whose login account is disabled is never suggested")
    void staffWithDisabledAccountAreNeverSuggested() {
        overloadedHousekeeping();
        freePersonWithAccount("Dina Disabled", maintenance, Role.STAFF, true, false, false);
        freePerson("Nia", maintenance);
        freePerson("Omar", maintenance);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertTrue(rec.suggestions().stream().noneMatch(s -> s.staffName().equals("Dina Disabled")));
    }

    @Test
    @DisplayName("A department whose only free people cannot sign in is not a donor")
    void departmentWithOnlyPendingStaffIsNotADonor() {
        overloadedHousekeeping();
        freePersonWithAccount("Una Unverified", maintenance, Role.STAFF, false, true, true);
        freePersonWithAccount("Sam Setup", maintenance, Role.STAFF, true, true, true);
        freePersonWithAccount("Uma Unverified", maintenance, Role.STAFF, false, true, true);

        WorkloadRecommendationsDto dto = run();

        assertTrue(dto.recommendations().isEmpty());
        assertEquals(1, dto.unsupported().size());
    }

    @Test
    @DisplayName("People who cannot be suggested still count as the free person a donor keeps in reserve")
    void ineligiblePeopleStillCountTowardTheReserve() {
        overloadedHousekeeping();
        freePerson("Nia", maintenance);
        freePersonWithAccount("Maya Manager", maintenance, Role.MANAGER, true, false, true);

        DepartmentRecommendation rec = forDept(run(), "HOUSEKEEPING");

        assertEquals(1, rec.suggestions().size());
        assertEquals("Nia", rec.suggestions().get(0).staffName());
        assertEquals(2, rec.suggestions().get(0).donorImpact().freeStaffBefore());
        assertEquals(1, rec.suggestions().get(0).donorImpact().freeStaffAfter());
    }

    @Test
    @DisplayName("The result states who is never suggested, and a missing account is not suggestable")
    void limitationsDescribeSuggestionEligibility() {
        overloadedHousekeeping();
        freePerson("Nia", maintenance);
        freePerson("Omar", maintenance);

        String limitations = String.join(" ", run().limitations());

        assertTrue(limitations.contains("Only active STAFF accounts that can sign in"));
        assertTrue(limitations.contains("Managers, admins and staff still waiting for email verification or credentials setup are never suggested"));
        Staff withoutAccount = new Staff();
        assertFalse(WorkloadRecommendationEngine.canBeSuggested(withoutAccount));
    }
}
