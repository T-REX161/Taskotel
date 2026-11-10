package com.hotel.workload.staff;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A manager may manage ordinary staff accounts, but not the staff record of a manager or an administrator account, through any of the
 * update, activate or deactivate endpoints. Runs on the in-memory test database; creates and removes its own records.
 */
@SpringBootTest
public class StaffRoleProtectionTest {

    private static final String PREFIX = "roleprot_";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;

    private MockMvc mockMvc;
    private Staff adminStaff;
    private Staff managerStaff;
    private Staff ordinaryStaff;
    private Staff callerManager;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
        adminStaff = create(Role.ADMIN, "admin");
        managerStaff = create(Role.MANAGER, "manager");
        ordinaryStaff = create(Role.STAFF, "staff");
        // The Manager who makes the requests: a valid Manager of the same (Front Desk) department, separate from the manager account being protected
        callerManager = create(Role.MANAGER, "caller");
    }

    @AfterEach
    public void cleanup() {
        for (User account : userRepository.findAll()) {
            if (account.getUsername().startsWith(PREFIX)) {
                staffRepository.findAll().stream()
                        .filter(s -> s.getUser() != null && s.getUser().getId().equals(account.getId()))
                        .forEach(staffRepository::delete);
                userRepository.delete(account);
            }
        }
    }

    private Staff create(Role role, String label) {
        User account = new User();
        account.setUsername(PREFIX + label);
        account.setEmail(PREFIX + label + "@hotel.test");
        account.setPassword("not-a-real-hash");
        account.setRole(role);
        account.setActive(true);
        account = userRepository.save(account);
        Staff staff = new Staff();
        staff.setUser(account);
        staff.setName("Role Protection " + label);
        staff.setEmail(PREFIX + label + "@hotel.test");
        staff.setDepartment(departmentRepository.findByCode("FRONT_DESK").orElseThrow());
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.save(staff);
    }

    private String body(Staff staff, String email, boolean active) {
        return """
                {"fullName": "%s", "email": "%s", "department": "FRONT_DESK", "availability": "AVAILABLE", "active": %s}
                """.formatted(staff.getName(), email, active);
    }

    private int send(String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(caller(role)).roles(role)).contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
    }

    /** A Manager request comes from the fixture Manager; other roles keep a plain user name. */
    private String caller(String role) {
        return "MANAGER".equals(role) ? callerManager.getUser().getUsername() : "caller";
    }

    private MockHttpServletRequestBuilder update(Staff staff, String email, boolean active) {
        return put("/api/staff/" + staff.getId()).content(body(staff, email, active));
    }

    private void assertUnchanged(Staff staff) {
        Staff stored = staffRepository.findById(staff.getId()).orElseThrow();
        User account = userRepository.findById(staff.getUser().getId()).orElseThrow();
        assertEquals(true, stored.isActive());
        assertEquals(true, account.isActive());
        assertEquals(staff.getEmail(), stored.getEmail());
        assertEquals(staff.getUser().getEmail(), account.getEmail());
    }

    @Test
    public void managerCannotChangeEmailOrActiveStatusOfAdminOrManagerAccounts() throws Exception {
        for (Staff target : new Staff[] {adminStaff, managerStaff}) {
            assertEquals(403, send("MANAGER", update(target, "attacker@evil.test", true)), "email change on " + target.getUser().getRole());
            assertEquals(403, send("MANAGER", update(target, target.getEmail(), false)), "deactivate by update on " + target.getUser().getRole());
            assertUnchanged(target);
        }
    }

    @Test
    public void managerCannotUseTheDeactivateOrActivateEndpointsOnThem() throws Exception {
        for (Staff target : new Staff[] {adminStaff, managerStaff}) {
            assertEquals(403, send("MANAGER", patch("/api/staff/" + target.getId() + "/deactivate")));
            assertUnchanged(target);
        }
        // An already-deactivated higher account cannot be switched back on by a manager either
        assertEquals(200, send("ADMIN", patch("/api/staff/" + managerStaff.getId() + "/deactivate")));
        assertEquals(403, send("MANAGER", patch("/api/staff/" + managerStaff.getId() + "/activate")));
        Staff stored = staffRepository.findById(managerStaff.getId()).orElseThrow();
        assertEquals(false, stored.isActive());
        assertEquals(false, userRepository.findById(managerStaff.getUser().getId()).orElseThrow().isActive());
    }

    @Test
    public void managerStillManagesOrdinaryStaff() throws Exception {
        String ordinaryEmail = ordinaryStaff.getUser().getEmail();
        assertEquals(200, send("MANAGER", update(ordinaryStaff, ordinaryEmail, true)));
        assertEquals(ordinaryEmail, userRepository.findById(ordinaryStaff.getUser().getId()).orElseThrow().getEmail(), "the login email is not changed by a staff update");
        assertEquals(200, send("MANAGER", patch("/api/staff/" + ordinaryStaff.getId() + "/deactivate")));
        assertEquals(false, userRepository.findById(ordinaryStaff.getUser().getId()).orElseThrow().isActive());
        assertEquals(200, send("MANAGER", patch("/api/staff/" + ordinaryStaff.getId() + "/activate")));
        assertEquals(true, staffRepository.findById(ordinaryStaff.getId()).orElseThrow().isActive());
    }

    @Test
    public void adminStillManagesEveryAccount() throws Exception {
        String managerEmail = managerStaff.getUser().getEmail();
        assertEquals(200, send("ADMIN", update(managerStaff, managerEmail, true)));
        assertEquals(managerEmail, userRepository.findById(managerStaff.getUser().getId()).orElseThrow().getEmail(), "the login email is not changed by a staff update");
        assertEquals(200, send("ADMIN", patch("/api/staff/" + adminStaff.getId() + "/deactivate")));
        assertEquals(false, userRepository.findById(adminStaff.getUser().getId()).orElseThrow().isActive());
        assertEquals(200, send("ADMIN", patch("/api/staff/" + adminStaff.getId() + "/activate")));
        assertEquals(true, userRepository.findById(adminStaff.getUser().getId()).orElseThrow().isActive());
    }

    private String read(String role, String url) throws Exception {
        var result = mockMvc.perform(get(url).with(user(caller(role)).roles(role))).andReturn().getResponse();
        return result.getStatus() + "|" + result.getContentAsString();
    }

    @Test
    public void adminListsEveryAccountKind() throws Exception {
        String body = read("ADMIN", "/api/staff");
        assertTrue(body.startsWith("200|"));
        for (String label : new String[] {"admin", "manager", "staff"}) {
            assertTrue(body.contains(PREFIX + label + "@hotel.test"), label + " record listed for an admin");
        }
        assertTrue(body.contains("\"role\":\"ADMIN\"") && body.contains("\"role\":\"MANAGER\""));
    }

    @Test
    public void managerListsOrdinaryStaffOnlyAndNoAdminOrManagerDetails() throws Exception {
        String body = read("MANAGER", "/api/staff");
        assertTrue(body.startsWith("200|"));
        assertTrue(body.contains(PREFIX + "staff@hotel.test"), "ordinary staff still listed");
        for (String label : new String[] {"admin", "manager"}) {
            assertFalse(body.contains(PREFIX + label), label + " username/email/name must be absent");
            assertFalse(body.contains(staffRepository.findById((label.equals("admin") ? adminStaff : managerStaff).getId()).orElseThrow().getId().toString()),
                    label + " staff id and user id must be absent");
        }
        assertFalse(body.contains("\"role\":\"ADMIN\"") || body.contains("\"role\":\"MANAGER\""));
        assertFalse(body.contains(adminStaff.getUser().getId().toString()) || body.contains(managerStaff.getUser().getId().toString()));
    }

    @Test
    public void noOtherReadPathExposesAProtectedRecordToAManager() throws Exception {
        String single = read("MANAGER", "/api/staff/" + adminStaff.getId());
        assertFalse(single.startsWith("200|"), "no single-record read exists: " + single.substring(0, 3));
        assertFalse(single.contains(PREFIX + "admin"));
        // a rejected write must not echo the protected record either
        var rejected = mockMvc.perform(update(adminStaff, "x@hotel.test", true).with(user(caller("MANAGER")).roles("MANAGER")).contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        assertEquals(403, rejected.getStatus());
        assertFalse(rejected.getContentAsString().contains(PREFIX + "admin"));
    }

    @Test
    public void staffRoleAndAnonymousCannotListStaff() throws Exception {
        assertTrue(read("STAFF", "/api/staff").startsWith("403|"));
        assertEquals(401, mockMvc.perform(get("/api/staff")).andReturn().getResponse().getStatus());
    }

    @Test
    public void usernameAvailabilityStillWorksForManagers() throws Exception {
        String taken = read("MANAGER", "/api/staff/username-availability?username=" + PREFIX + "admin");
        assertEquals("200|{\"available\":false}", taken, "answers only whether it is free");
        assertEquals("200|{\"available\":true}", read("MANAGER", "/api/staff/username-availability?username=" + PREFIX + "free"));
    }

    @Test
    public void staffRoleStillCannotReachStaffManagementAtAll() throws Exception {
        assertEquals(403, send("STAFF", update(ordinaryStaff, "x@hotel.test", true)));
        assertEquals(403, send("STAFF", patch("/api/staff/" + ordinaryStaff.getId() + "/deactivate")));
        assertUnchanged(ordinaryStaff);
    }
}
