package com.hotel.workload.security;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.NotificationCategory;
import com.hotel.workload.notification.NotificationPreference;
import com.hotel.workload.notification.NotificationPreferenceRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Activate / deactivate / remove through /api/users/{id}/..., for accounts with and without a staff record. Each test runs in a
 * transaction that is rolled back, so no data is left behind (and nothing outside the test database is touched).
 */
@SpringBootTest
@Transactional
public class UserAccountManagementTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = account("uam_admin", Role.ADMIN, true);
    }

    private User account(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private Staff withStaffRecord(User user) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Staff " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(user.isActive());
        return staffRepository.saveAndFlush(staff);
    }

    private Task taskCreatedBy(User creator, TaskStatus status) {
        Task task = new Task();
        task.setTitle("T " + status);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setDepartment(TestDepartments.HOUSEKEEPING);
        task.setCreatedBy(creator);
        return taskRepository.saveAndFlush(task);
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private boolean active(User user) {
        return userRepository.findById(user.getId()).orElseThrow().isActive();
    }

    // ── accounts without a staff record ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Orphaned accounts (no staff record) of every role can be deactivated and reactivated by an admin; nothing else on them changes")
    void orphanedAccountsCanBeToggled() throws Exception {
        for (Role role : Role.values()) {
            User target = account("uam_orphan_" + role.name().toLowerCase(), role, true);
            String hash = target.getPassword();
            assertTrue(staffRepository.findByUserUsername(target.getUsername()).isEmpty());

            MvcResult off = call(patch("/api/users/" + target.getId() + "/deactivate"), admin);
            assertEquals(200, off.getResponse().getStatus(), role + ": " + text(off));
            assertTrue(text(off).contains("\"active\":false"), text(off));
            assertFalse(active(target));

            MvcResult on = call(patch("/api/users/" + target.getId() + "/activate"), admin);
            assertEquals(200, on.getResponse().getStatus(), role + ": " + text(on));
            assertTrue(active(target));

            User after = userRepository.findById(target.getId()).orElseThrow();
            assertEquals(hash, after.getPassword());
            assertEquals(role, after.getRole());
            assertEquals(target.getEmail(), after.getEmail());
            assertEquals(0, staffRepository.findAll().stream().filter(s -> s.getUser().getId().equals(target.getId())).count(), "no staff record is invented");
        }
    }

    @Test
    @DisplayName("An orphaned account can be removed once it is inactive; its own notification choices go with it, and nothing else is deleted")
    void orphanedAccountCanBeRemoved() throws Exception {
        User target = account("uam_remove", Role.STAFF, false);
        preferences.saveAndFlush(new NotificationPreference(target.getId(), NotificationCategory.TASK_STATUS, true, true));
        User bystander = account("uam_bystander", Role.STAFF, true);
        preferences.saveAndFlush(new NotificationPreference(bystander.getId(), NotificationCategory.TASK_STATUS, true, true));

        MvcResult result = call(delete("/api/users/" + target.getId()), admin);

        assertEquals(204, result.getResponse().getStatus(), text(result));
        assertTrue(userRepository.findById(target.getId()).isEmpty());
        assertTrue(preferences.findByUserId(target.getId()).isEmpty());
        assertEquals(1, preferences.findByUserId(bystander.getId()).size(), "other accounts' data is untouched");
        assertTrue(userRepository.findById(bystander.getId()).isPresent());
    }

    @Test
    @DisplayName("An active account cannot be removed: it must be deactivated first")
    void activeAccountCannotBeRemoved() throws Exception {
        User target = account("uam_active", Role.MANAGER, true);
        MvcResult result = call(delete("/api/users/" + target.getId()), admin);
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(text(result).contains(UserAccountService.REMOVE_ACTIVE_MESSAGE), text(result));
        assertTrue(userRepository.findById(target.getId()).isPresent());
    }

    @Test
    @DisplayName("An account that created tasks is never deleted (no cascade): the refusal explains why and points to deactivation, and the tasks stay")
    void accountWithTasksIsProtected() throws Exception {
        User creator = account("uam_creator", Role.MANAGER, false);
        Task task = taskCreatedBy(creator, TaskStatus.COMPLETED);

        MvcResult result = call(delete("/api/users/" + creator.getId()), admin);

        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(UserAccountService.HAS_TASKS_MESSAGE), text(result));
        assertTrue(userRepository.findById(creator.getId()).isPresent());
        assertTrue(taskRepository.findById(task.getId()).isPresent());
        // and the alternative that was offered works
        assertEquals(200, call(patch("/api/users/" + creator.getId() + "/activate"), admin).getResponse().getStatus());
        assertEquals(200, call(patch("/api/users/" + creator.getId() + "/deactivate"), admin).getResponse().getStatus());
    }

    // ── accounts with a staff record: existing rules still apply ─────────────────────────────────────

    @Test
    @DisplayName("An account with a staff record can be managed too, and the staff record follows")
    void staffLinkedAccountsStillWork() throws Exception {
        User target = account("uam_linked", Role.STAFF, true);
        Staff staff = withStaffRecord(target);

        assertEquals(200, call(patch("/api/users/" + target.getId() + "/deactivate"), admin).getResponse().getStatus());
        assertFalse(active(target));
        assertFalse(staffRepository.findById(staff.getId()).orElseThrow().isActive());

        assertEquals(200, call(patch("/api/users/" + target.getId() + "/activate"), admin).getResponse().getStatus());
        assertTrue(active(target));
        assertTrue(staffRepository.findById(staff.getId()).orElseThrow().isActive());

        assertEquals(200, call(patch("/api/users/" + target.getId() + "/deactivate"), admin).getResponse().getStatus());
        assertEquals(204, call(delete("/api/users/" + target.getId()), admin).getResponse().getStatus());
        assertTrue(userRepository.findById(target.getId()).isEmpty());
        assertTrue(staffRepository.findById(staff.getId()).isEmpty());
    }

    @Test
    @DisplayName("Staff safety rules are not bypassed: no deactivation with active assignments, no removal with assignment history")
    void staffRulesStillProtectRelatedRecords() throws Exception {
        User target = account("uam_busy", Role.STAFF, true);
        Staff staff = withStaffRecord(target);
        Task task = taskCreatedBy(admin, TaskStatus.ASSIGNED);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment.setAssignedAt(LocalDateTime.now());
        assignmentRepository.saveAndFlush(assignment);

        MvcResult refused = call(patch("/api/users/" + target.getId() + "/deactivate"), admin);
        assertEquals(409, refused.getResponse().getStatus(), text(refused));
        assertTrue(active(target), "still active");

        task.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(task);
        assertEquals(200, call(patch("/api/users/" + target.getId() + "/deactivate"), admin).getResponse().getStatus());
        MvcResult removal = call(delete("/api/users/" + target.getId()), admin);
        assertEquals(409, removal.getResponse().getStatus(), text(removal));
        assertTrue(text(removal).contains("Deactivation should be used instead"), text(removal));
        assertTrue(userRepository.findById(target.getId()).isPresent());
        assertTrue(assignmentRepository.findById(assignment.getId()).isPresent(), "history is kept");
    }

    // ── safety and authorization ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An admin cannot deactivate or remove their own account, and the last active administrator cannot be deactivated")
    void selfAndLastAdminAreProtected() throws Exception {
        MvcResult own = call(patch("/api/users/" + admin.getId() + "/deactivate"), admin);
        assertEquals(400, own.getResponse().getStatus());
        assertTrue(text(own).contains(UserAccountService.OWN_ACCOUNT_MESSAGE));
        assertEquals(400, call(delete("/api/users/" + admin.getId()), admin).getResponse().getStatus());
        assertTrue(active(admin));

        // deactivate every other active admin so exactly one is left, then try to deactivate it as a different (inactive-proof) caller
        userRepository.findAll().stream().filter(u -> u.getRole() == Role.ADMIN && u.isActive() && !u.getId().equals(admin.getId()))
                .forEach(u -> userRepository.updateActive(u.getId(), false));
        User caller = account("uam_caller", Role.ADMIN, true);
        userRepository.updateActive(admin.getId(), true);
        userRepository.updateActive(caller.getId(), false); // the caller's token still works in this test: it is just an identity
        MvcResult last = call(patch("/api/users/" + admin.getId() + "/deactivate"), caller);
        assertEquals(409, last.getResponse().getStatus(), text(last));
        assertTrue(text(last).contains(UserAccountService.LAST_ADMIN_MESSAGE));
        assertTrue(active(admin));
    }

    @Test
    @DisplayName("Only administrators can manage accounts: Manager, Staff and unauthenticated callers are refused and nothing changes")
    void onlyAdminsMayManage() throws Exception {
        User target = account("uam_target", Role.STAFF, true);
        User manager = account("uam_manager", Role.MANAGER, true);
        User staff = account("uam_staffer", Role.STAFF, true);
        for (User caller : new User[] {manager, staff}) {
            assertEquals(403, call(patch("/api/users/" + target.getId() + "/deactivate"), caller).getResponse().getStatus(), caller.getRole().name());
            assertEquals(403, call(patch("/api/users/" + target.getId() + "/activate"), caller).getResponse().getStatus());
            assertEquals(403, call(delete("/api/users/" + target.getId()), caller).getResponse().getStatus());
            assertEquals(403, call(get("/api/users"), caller).getResponse().getStatus());
        }
        int anon = mockMvc.perform(patch("/api/users/" + target.getId() + "/deactivate")).andReturn().getResponse().getStatus();
        assertTrue(anon == 401 || anon == 403, "status " + anon);
        assertTrue(active(target));
        assertTrue(userRepository.findById(target.getId()).isPresent());
    }

    @Test
    @DisplayName("An unknown account is a clean 404, and the user list still returns everyone, with or without a staff record")
    void unknownAccountAndListing() throws Exception {
        assertEquals(404, call(patch("/api/users/" + java.util.UUID.randomUUID() + "/deactivate"), admin).getResponse().getStatus());
        assertEquals(404, call(delete("/api/users/" + java.util.UUID.randomUUID()), admin).getResponse().getStatus());

        User orphan = account("uam_list_orphan", Role.STAFF, true);
        User linked = account("uam_list_linked", Role.STAFF, true);
        withStaffRecord(linked);
        String list = text(call(get("/api/users"), admin));
        assertTrue(list.contains(orphan.getUsername()) && list.contains(linked.getUsername()), "everyone is listed");
    }
}
