package com.hotel.workload.workload;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Manager entry point of the pure engine, with no database: recommendations for every department a Manager manages, ranked together,
 * with a donor redacted unless the Manager manages the donor's department too.
 */
public class WorkloadRecommendationManagerEngineTest {

    private final LocalDate today = LocalDate.of(2026, 11, 6);
    private final LocalDateTime now = today.atStartOfDay();

    private final Department home = new Department("HOME", "Home");
    private final Department granted = new Department("GRANT", "Granted");
    private final Department outsider = new Department("OUT", "Outsider");
    private final Department donor = new Department("DON", "Donor");
    private final Department managedDonor = new Department("MDON", "Managed donor");
    private final List<Staff> staff = new ArrayList<>();

    private Staff person(String name, Department department) {
        Staff member = new Staff();
        member.setId(UUID.randomUUID());
        member.setName(name);
        member.setDepartment(department);
        member.setAvailability(Availability.AVAILABLE);
        member.setActive(true);
        User account = new User();
        account.setUsername(name.toLowerCase());
        account.setRole(Role.STAFF);
        account.setActive(true);
        account.setEmailVerified(true);
        account.setCredentialsSetupRequired(false);
        member.setUser(account);
        staff.add(member);
        return member;
    }

    private void people(String prefix, Department department, int count) {
        for (int i = 1; i <= count; i++) person(prefix + i, department);
    }

    private static Task task(String title, Department department) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle(title);
        task.setDepartment(department);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(20);
        return task;
    }

    private static List<Task> tasks(String prefix, Department department, int count) {
        List<Task> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(task(prefix + i, department));
        return list;
    }

    private List<Department> all() {
        return List.of(home, granted, outsider, donor, managedDonor);
    }

    @Test
    @DisplayName("With one managed department the result is exactly the single-receiver result (a Manager without grants is unchanged)")
    public void oneManagedDepartmentEqualsTheSingleReceiverResult() {
        people("Donor", donor, 4);
        people("Home", home, 1);
        List<Task> waiting = tasks("home task ", home, 3);

        WorkloadRecommendationsDto single = WorkloadRecommendationEngine.recommendForReceiver(home, all(), List.copyOf(staff), waiting,
                Map.of("OUT", 2, "DON", 0), Set.of(), today, now);
        WorkloadRecommendationsDto manager = WorkloadRecommendationEngine.recommendForManager(List.of(home), all(), List.copyOf(staff),
                Map.of("HOME", waiting), Map.of("OUT", 2, "DON", 0, "GRANT", 0, "MDON", 0), Set.of(), today, now);

        assertEquals(single, manager);
        assertEquals(1, manager.recommendations().size());
        assertTrue(manager.recommendations().get(0).suggestions().stream().allMatch(s -> s.staffId() == null && s.donorImpact() == null),
                "every donor is redacted when nothing but the Manager's own department is managed");
    }

    @Test
    @DisplayName("Every managed department with uncovered work is a receiver; an unmanaged department under pressure is not")
    public void everyManagedDepartmentIsAReceiver() {
        people("Donor", donor, 6);
        List<Task> homeTasks = tasks("home ", home, 2);
        List<Task> grantedTasks = tasks("granted ", granted, 2);

        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForManager(List.of(home, granted), all(), List.copyOf(staff),
                Map.of("HOME", homeTasks, "GRANT", grantedTasks), Map.of("OUT", 9), Set.of(), today, now);

        Set<String> receivers = new java.util.HashSet<>();
        result.recommendations().forEach(r -> receivers.add(r.departmentCode()));
        result.unsupported().forEach(u -> receivers.add(u.departmentCode()));
        assertEquals(Set.of("HOME", "GRANT"), receivers, "OUT has nine waiting tasks but is not managed");
        assertFalse(result.toString().contains("Outsider"), "nothing is said about it");
    }

    @Test
    @DisplayName("A donor in a managed department is shown in full; any other donor is redacted; nobody is suggested twice")
    public void donorsAreRedactedPerDepartment() {
        people("Plain", donor, 4);
        people("Managed", managedDonor, 4);
        List<Task> homeTasks = tasks("home ", home, 3);

        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForManager(List.of(home, managedDonor), all(), List.copyOf(staff),
                Map.of("HOME", homeTasks, "MDON", List.of()), Map.of(), Set.of(), today, now);

        List<WorkloadRecommendationsDto.Suggestion> suggestions = result.recommendations().get(0).suggestions();
        assertEquals(3, suggestions.size());
        Set<String> names = new java.util.HashSet<>();
        int managed = 0;
        int plain = 0;
        for (WorkloadRecommendationsDto.Suggestion suggestion : suggestions) {
            assertTrue(names.add(suggestion.staffName()), "nobody is suggested twice");
            if (suggestion.homeDepartmentName().equals("Managed donor")) {
                managed++;
                assertEquals("MDON", suggestion.homeDepartmentCode());
                assertTrue(suggestion.staffId() != null && suggestion.donorImpact() != null);
                assertTrue(suggestion.donorImpact().freeStaffBefore() > suggestion.donorImpact().freeStaffAfter());
            } else {
                plain++;
                assertNull(suggestion.staffId());
                assertNull(suggestion.homeDepartmentCode());
                assertNull(suggestion.donorImpact());
                assertEquals(2, suggestion.reasons().size());
            }
        }
        assertTrue(managed > 0 && plain > 0);
    }

    @Test
    @DisplayName("A managed department that is not active, or not known, is ignored; with nothing managed the result is empty")
    public void inactiveOrUnknownManagedDepartmentsAreIgnored() {
        Department closed = new Department("OFF", "Closed");
        closed.setActive(false);
        people("Donor", donor, 4);
        List<Department> departments = new ArrayList<>(all());
        departments.add(closed);

        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForManager(List.of(home, closed), departments, List.copyOf(staff),
                Map.of("HOME", tasks("home ", home, 2), "OFF", tasks("closed ", closed, 5)), Map.of(), Set.of(), today, now);
        assertEquals(1, result.recommendations().size() + result.unsupported().size());
        assertFalse(result.toString().contains("Closed"));

        WorkloadRecommendationsDto none = WorkloadRecommendationEngine.recommendForManager(List.of(closed), departments, List.copyOf(staff),
                Map.of("OFF", tasks("closed ", closed, 5)), Map.of(), Set.of(), today, now);
        assertTrue(none.recommendations().isEmpty() && none.unsupported().isEmpty());
        assertTrue(WorkloadRecommendationEngine.recommendForManager(List.of(), departments, List.copyOf(staff), new HashMap<>(), Map.of(), Set.of(), today, now)
                .recommendations().isEmpty());
    }

    @Test
    @DisplayName("A task of another department passed in a managed department's list never counts as its waiting work")
    public void tasksAreKeptWithTheirOwnDepartment() {
        people("Donor", donor, 4);
        List<Task> mixed = new ArrayList<>(tasks("home ", home, 1));
        mixed.addAll(tasks("stray ", outsider, 4));
        WorkloadRecommendationsDto result = WorkloadRecommendationEngine.recommendForManager(List.of(home), all(), List.copyOf(staff),
                Map.of("HOME", mixed), Map.of(), Set.of(), today, now);
        assertEquals(1, result.recommendations().get(0).pressure().waitingTasks());
        assertFalse(result.toString().contains("stray"));
    }
}
