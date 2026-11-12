package com.hotel.workload.security;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * An administrator cannot switch off their own account, and the last active administrator can never be switched off, on every route
 * that can deactivate an account: PATCH /api/staff/{id}/deactivate, PUT /api/staff/{id} with active=false and PATCH /api/users/{id}/deactivate.
 * Real security chain and database (H2); every test rolls back. A refusal must leave the Staff record AND the account active.
 *
 * <p>Every other active administrator already in the test database is switched off first, so each test controls exactly how many are active
 * (all rolled back). Where a test needs "somebody else" to be the caller of the last administrator, the caller is an inactive administrator
 * account: only its identity matters to MockMvc, exactly as in UserAccountManagementTest.
 */
@SpringBootTest
@Transactional
public class AdminDeactivationProtectionTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userRepository.findAll().stream().filter(u -> u.getRole() == Role.ADMIN && u.isActive())
                .forEach(u -> userRepository.updateActive(u.getId(), false));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private Staff staffRecord(User user) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Staff " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setPhone("555-0100");
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(user.isActive());
        return staffRepository.saveAndFlush(staff);
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request, User as) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
    }

    private MvcResult deactivateViaStaff(Staff target, User as) throws Exception {
        return call(patch("/api/staff/" + target.getId() + "/deactivate"), as);
    }

    private MvcResult deactivateViaUsers(User target, User as) throws Exception {
        return call(patch("/api/users/" + target.getId() + "/deactivate"), as);
    }

    /** The ordinary Staff edit, as the Edit Staff form sends it. */
    private MvcResult editViaStaff(Staff target, boolean active, User as) throws Exception {
        String body = "{\"fullName\": \"" + target.getName() + "\", \"email\": \"" + target.getEmail() + "\", \"phone\": \"555-0100\", \"department\": \""
                + TestDepartments.HOUSEKEEPING.getCode() + "\", \"availability\": \"AVAILABLE\", \"active\": " + active + "}";
        return call(put("/api/staff/" + target.getId()).content(body), as);
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private boolean accountActive(User user) {
        return userRepository.findById(user.getId()).orElseThrow().isActive();
    }

    private boolean staffActive(Staff staff) {
        return staffRepository.findById(staff.getId()).orElseThrow().isActive();
    }

    /** Both flags still read active from the database (not from the entities in this test's persistence context). */
    private void assertBothActive(User user, Staff staff, String why) {
        userRepository.flush();
        staffRepository.flush();
        assertTrue(accountActive(user), why + ": account still active");
        assertTrue(staffActive(staff), why + ": staff record still active");
    }

    private void giveActiveWork(Staff staff) {
        Task task = new Task();
        task.setTitle("Active work");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(TestDepartments.HOUSEKEEPING);
        task.setCreatedBy(staff.getUser());
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    // ── own account ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. An admin cannot deactivate themselves through the Staff endpoint (another admin stays active, so only the own-account rule applies)")
    void adminCannotDeactivateThemselvesThroughStaff() throws Exception {
        User me = account("adp_me", Role.ADMIN, true);
        Staff myStaff = staffRecord(me);
        account("adp_other", Role.ADMIN, true);

        MvcResult result = deactivateViaStaff(myStaff, me);
        assertEquals(400, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.OWN_ACCOUNT_MESSAGE), text(result));
        assertBothActive(me, myStaff, "refused");
    }

    @Test
    @DisplayName("2. An admin cannot deactivate themselves through the Users endpoint, with or without a Staff record")
    void adminCannotDeactivateThemselvesThroughUsers() throws Exception {
        User me = account("adp_me2", Role.ADMIN, true);
        Staff myStaff = staffRecord(me);
        User meNoStaff = account("adp_me3", Role.ADMIN, true);

        MvcResult result = deactivateViaUsers(me, me);
        assertEquals(400, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.OWN_ACCOUNT_MESSAGE), text(result));
        assertBothActive(me, myStaff, "refused");

        MvcResult noStaff = deactivateViaUsers(meNoStaff, meNoStaff);
        assertEquals(400, noStaff.getResponse().getStatus(), text(noStaff));
        assertTrue(accountActive(meNoStaff));
    }

    @Test
    @DisplayName("2b. The same own-account rule holds when an admin edits their own Staff record with active=false")
    void adminCannotDeactivateThemselvesThroughStaffEdit() throws Exception {
        User me = account("adp_me4", Role.ADMIN, true);
        Staff myStaff = staffRecord(me);
        account("adp_other4", Role.ADMIN, true);

        MvcResult result = editViaStaff(myStaff, false, me);
        assertEquals(400, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.OWN_ACCOUNT_MESSAGE), text(result));
        assertBothActive(me, myStaff, "refused");
        // Saving the same form with the account kept active is still fine
        assertEquals(200, editViaStaff(myStaff, true, me).getResponse().getStatus());
    }

    // ── last active admin ────────────────────────────────────────────────────

    @Test
    @DisplayName("3. The last active admin cannot be deactivated through Staff deactivation")
    void lastAdminCannotBeDeactivatedThroughStaff() throws Exception {
        User last = account("adp_last", Role.ADMIN, true);
        Staff lastStaff = staffRecord(last);
        User caller = account("adp_caller", Role.ADMIN, false); // identity only: the only active admin is the target

        MvcResult result = deactivateViaStaff(lastStaff, caller);
        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.LAST_ADMIN_MESSAGE), text(result));
        assertBothActive(last, lastStaff, "refused");
    }

    @Test
    @DisplayName("4. The last active admin cannot be deactivated through Staff editing with active=false")
    void lastAdminCannotBeDeactivatedThroughStaffEdit() throws Exception {
        User last = account("adp_last2", Role.ADMIN, true);
        Staff lastStaff = staffRecord(last);
        User caller = account("adp_caller2", Role.ADMIN, false);

        MvcResult result = editViaStaff(lastStaff, false, caller);
        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.LAST_ADMIN_MESSAGE), text(result));
        assertBothActive(last, lastStaff, "refused");
    }

    @Test
    @DisplayName("4b. The last active admin cannot be deactivated through the Users endpoint either (with a Staff record: no change to either flag)")
    void lastAdminCannotBeDeactivatedThroughUsers() throws Exception {
        User last = account("adp_last3", Role.ADMIN, true);
        Staff lastStaff = staffRecord(last);
        User caller = account("adp_caller3", Role.ADMIN, false);

        MvcResult result = deactivateViaUsers(last, caller);
        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains(AdminAccountGuard.LAST_ADMIN_MESSAGE), text(result));
        assertBothActive(last, lastStaff, "refused");
    }

    // ── what must still work ─────────────────────────────────────────────────

    @Test
    @DisplayName("5. An admin can deactivate another admin while at least one other active admin remains, by every route")
    void adminCanDeactivateAnotherAdminWhenOthersRemain() throws Exception {
        User me = account("adp_ok_me", Role.ADMIN, true);
        User second = account("adp_ok_2", Role.ADMIN, true);
        Staff secondStaff = staffRecord(second);
        User third = account("adp_ok_3", Role.ADMIN, true);
        Staff thirdStaff = staffRecord(third);
        User fourth = account("adp_ok_4", Role.ADMIN, true);
        Staff fourthStaff = staffRecord(fourth);

        // Staff deactivation route: four active, one is switched off
        assertEquals(200, deactivateViaStaff(secondStaff, me).getResponse().getStatus());
        userRepository.flush();
        staffRepository.flush();
        assertFalse(accountActive(second));
        assertFalse(staffActive(secondStaff), "the Staff record and the account stay in step");

        // Staff edit route
        assertEquals(200, editViaStaff(thirdStaff, false, me).getResponse().getStatus());
        userRepository.flush();
        staffRepository.flush();
        assertFalse(accountActive(third));
        assertFalse(staffActive(thirdStaff));

        // Users route: two are left (me, fourth). Switching off the fourth leaves me, so it is allowed...
        assertEquals(200, deactivateViaUsers(fourth, me).getResponse().getStatus());
        userRepository.flush();
        staffRepository.flush();
        assertFalse(accountActive(fourth));
        assertFalse(staffActive(fourthStaff));
        // ...and now `me` is the last one: nobody else can switch them off
        User inactiveCaller = account("adp_ok_caller", Role.ADMIN, false);
        MvcResult last = deactivateViaUsers(me, inactiveCaller);
        assertEquals(409, last.getResponse().getStatus(), text(last));
        assertTrue(accountActive(me));
    }

    @Test
    @DisplayName("6. Normal Staff deactivation still works, by the Staff route and by the Users route, and keeps both flags in step")
    void normalStaffDeactivationStillWorks() throws Exception {
        User admin = account("adp_n_admin", Role.ADMIN, true);
        User one = account("adp_n_staff1", Role.STAFF, true);
        Staff oneStaff = staffRecord(one);
        User two = account("adp_n_staff2", Role.STAFF, true);
        Staff twoStaff = staffRecord(two);

        assertEquals(200, deactivateViaStaff(oneStaff, admin).getResponse().getStatus());
        assertEquals(200, deactivateViaUsers(two, admin).getResponse().getStatus());
        userRepository.flush();
        staffRepository.flush();
        assertFalse(accountActive(one));
        assertFalse(staffActive(oneStaff));
        assertFalse(accountActive(two));
        assertFalse(staffActive(twoStaff));
    }

    @Test
    @DisplayName("7. The active-assignment rule still refuses deactivation, on every route, and changes neither flag")
    void activeAssignmentRestrictionStillWorks() throws Exception {
        User admin = account("adp_a_admin", Role.ADMIN, true);
        User worker = account("adp_a_staff", Role.STAFF, true);
        Staff workerStaff = staffRecord(worker);
        giveActiveWork(workerStaff);

        MvcResult viaStaff = deactivateViaStaff(workerStaff, admin);
        assertEquals(409, viaStaff.getResponse().getStatus(), text(viaStaff));
        assertTrue(text(viaStaff).contains("active task assignments"), text(viaStaff));
        MvcResult viaEdit = editViaStaff(workerStaff, false, admin);
        assertEquals(409, viaEdit.getResponse().getStatus(), text(viaEdit));
        MvcResult viaUsers = deactivateViaUsers(worker, admin);
        assertEquals(409, viaUsers.getResponse().getStatus(), text(viaUsers));
        assertBothActive(worker, workerStaff, "every route refused");
    }

    @Test
    @DisplayName("8. A refused deactivation changes nothing, and an admin target with active work is refused by the assignment rule, not the guard, when others remain")
    void failedDeactivationLeavesBothFlagsUnchanged() throws Exception {
        User me = account("adp_f_me", Role.ADMIN, true);
        User other = account("adp_f_other", Role.ADMIN, true);
        Staff otherStaff = staffRecord(other);
        giveActiveWork(otherStaff);

        MvcResult result = deactivateViaStaff(otherStaff, me);
        assertEquals(409, result.getResponse().getStatus(), text(result));
        assertTrue(text(result).contains("active task assignments"), text(result));
        assertBothActive(other, otherStaff, "refused by the assignment rule");
        assertTrue(accountActive(me));
    }

    @Test
    @DisplayName("9. An account without a Staff record can still be deactivated through Users management (an admin when others remain, and other roles)")
    void accountWithoutStaffRecordStillWorksThroughUsers() throws Exception {
        User me = account("adp_u_me", Role.ADMIN, true);
        User otherAdmin = account("adp_u_admin", Role.ADMIN, true); // no Staff record
        User manager = account("adp_u_manager", Role.MANAGER, true); // no Staff record
        User staff = account("adp_u_staff", Role.STAFF, true);       // no Staff record

        assertEquals(200, deactivateViaUsers(otherAdmin, me).getResponse().getStatus());
        assertEquals(200, deactivateViaUsers(manager, me).getResponse().getStatus());
        assertEquals(200, deactivateViaUsers(staff, me).getResponse().getStatus());
        userRepository.flush();
        assertFalse(accountActive(otherAdmin));
        assertFalse(accountActive(manager));
        assertFalse(accountActive(staff));
        assertTrue(accountActive(me));
    }

    @Test
    @DisplayName("10. Only an administrator may call the deactivation routes: a Manager is still refused (403) and nothing changes")
    void managerStillCannotDeactivateAnAdmin() throws Exception {
        User manager = account("adp_m_mgr", Role.MANAGER, true);
        Staff managerStaff = staffRecord(manager);
        User admin = account("adp_m_admin", Role.ADMIN, true);
        Staff adminStaff = staffRecord(admin);
        account("adp_m_admin2", Role.ADMIN, true);

        assertEquals(403, deactivateViaStaff(adminStaff, manager).getResponse().getStatus());
        assertEquals(403, deactivateViaUsers(admin, manager).getResponse().getStatus());
        assertBothActive(admin, adminStaff, "refused");
        assertTrue(managerStaff.isActive());
    }
}
