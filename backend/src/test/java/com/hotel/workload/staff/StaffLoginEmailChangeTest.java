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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Stage 1 (login email security): an Admin or Manager can no longer change a Staff member's login email through the staff update.
 * The only way to change it is the account holder's own verified self-service flow (covered by the email-change tests). Other edits keep
 * working, department isolation still answers first, and staff creation keeps its email rules. Creates and removes its own records.
 */
@SpringBootTest
public class StaffLoginEmailChangeTest {

    private static final String PREFIX = "loginmail_";
    private static final String OWN_DEPT = "FRONT_DESK";
    private static final String OTHER_DEPT = "HOUSEKEEPING";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private StaffService staffService;

    private MockMvc mockMvc;
    private Staff manager;
    private Staff target;
    private Staff outsider;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
        manager = create(Role.MANAGER, "manager", OWN_DEPT);
        target = create(Role.STAFF, "target", OWN_DEPT);
        outsider = create(Role.STAFF, "outsider", OTHER_DEPT);
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
        staffRepository.findAll().stream().filter(s -> s.getEmail() != null && s.getEmail().startsWith(PREFIX + "new"))
                .forEach(staffRepository::delete);
    }

    private Staff create(Role role, String label, String department) {
        User account = new User();
        account.setUsername(PREFIX + label);
        account.setEmail(PREFIX + label + "@hotel.test");
        account.setPassword("not-a-real-hash");
        account.setRole(role);
        account.setActive(true);
        account = userRepository.save(account);
        Staff staff = new Staff();
        staff.setUser(account);
        staff.setName("Login Mail " + label);
        staff.setEmail(PREFIX + label + "@hotel.test");
        staff.setDepartment(departmentRepository.findByCode(department).orElseThrow());
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.save(staff);
    }

    private String body(Staff staff, String name, String email, String department) {
        return """
                {"fullName": "%s", "email": "%s", "department": "%s", "availability": "AVAILABLE", "active": true}
                """.formatted(name, email, department);
    }

    private MockHttpServletResponse send(String role, String body, Staff on) throws Exception {
        String caller = "MANAGER".equals(role) ? manager.getUser().getUsername() : PREFIX + "admincaller";
        MockHttpServletRequestBuilder request = put("/api/staff/" + on.getId()).content(body).contentType(MediaType.APPLICATION_JSON)
                .with(user(caller).roles(role));
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void assertEmailsUnchanged(Staff staff) {
        assertEquals(staff.getEmail(), staffRepository.findById(staff.getId()).orElseThrow().getEmail());
        assertEquals(staff.getUser().getEmail(), userRepository.findById(staff.getUser().getId()).orElseThrow().getEmail());
    }

    @Test
    public void adminChangingAnotherStaffLoginEmailIsRejectedWithAClearMessage() throws Exception {
        MockHttpServletResponse response = send("ADMIN", body(target, "Renamed", "new_" + PREFIX + "admin@evil.test", OWN_DEPT), target);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("account holder"), response.getContentAsString());
        assertEmailsUnchanged(target);
        assertEquals(target.getName(), staffRepository.findById(target.getId()).orElseThrow().getName(), "nothing else was saved either");
    }

    @Test
    public void managerChangingStaffLoginEmailInOwnDepartmentIsRejected() throws Exception {
        MockHttpServletResponse response = send("MANAGER", body(target, "Renamed", "new_" + PREFIX + "mgr@evil.test", OWN_DEPT), target);
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("account holder"), response.getContentAsString());
        assertEmailsUnchanged(target);
    }

    @Test
    public void managerOutsideTheDepartmentStillGetsTheIsolationAnswerNotTheEmailAnswer() throws Exception {
        MockHttpServletResponse response = send("MANAGER", body(outsider, "Renamed", "new_" + PREFIX + "x@evil.test", OTHER_DEPT), outsider);
        assertEquals(404, response.getStatus());
        assertEmailsUnchanged(outsider);
    }

    @Test
    public void adminAndManagerCanStillEditOtherFieldsWhenTheEmailIsUnchanged() throws Exception {
        assertEquals(200, send("ADMIN", body(target, "By Admin", target.getEmail(), OWN_DEPT), target).getStatus());
        assertEquals("By Admin", staffRepository.findById(target.getId()).orElseThrow().getName());
        assertEquals(200, send("MANAGER", body(target, "By Manager", target.getEmail(), OWN_DEPT), target).getStatus());
        assertEquals("By Manager", staffRepository.findById(target.getId()).orElseThrow().getName());
        assertEmailsUnchanged(target);
    }

    @Test
    public void aDifferenceOfCaseOnlyIsNotAChange() throws Exception {
        String sameAddress = target.getEmail().toUpperCase();
        assertEquals(200, send("ADMIN", body(target, "Same Address", sameAddress, OWN_DEPT), target).getStatus());
        assertEmailsUnchanged(target);
    }

    @Test
    public void anAdminCannotChangeTheirOwnLoginEmailThroughTheStaffUpdateEither() throws Exception {
        Staff admin = create(Role.ADMIN, "adminself", OWN_DEPT);
        assertEquals(400, send("ADMIN", body(admin, "Admin Self", "new_" + PREFIX + "self@evil.test", OWN_DEPT), admin).getStatus());
        assertEmailsUnchanged(admin);
    }

    @Test
    public void theServiceItselfRefusesTheChange() {
        UpdateStaffRequest request = new UpdateStaffRequest();
        request.setFullName("Direct");
        request.setEmail("new_" + PREFIX + "direct@evil.test");
        request.setDepartment(OWN_DEPT);
        request.setAvailability(Availability.AVAILABLE);
        request.setActive(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(PREFIX + "admincaller", "n/a",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> staffService.updateStaff(target.getId(), request));
            assertEquals(400, thrown.getStatusCode().value());
            assertEmailsUnchanged(target);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void creatingStaffStillTakesAnEmailAndStillRejectsADuplicate() throws Exception {
        String create = """
                {"fullName": "Created", "username": "%snewuser", "email": "%snew_created@hotel.test", "department": "%s",
                 "availability": "AVAILABLE", "role": "STAFF", "phone": "5550001", "temporaryPassword": "Temp#Pass123!"}
                """.formatted(PREFIX, PREFIX, OWN_DEPT);
        var admin = user(PREFIX + "admincaller").roles("ADMIN");
        int first = mockMvc.perform(post("/api/staff").content(create).contentType(MediaType.APPLICATION_JSON).with(admin))
                .andReturn().getResponse().getStatus();
        assertTrue(first == 200 || first == 201, "creation status " + first);
        String duplicate = create.replace(PREFIX + "newuser", PREFIX + "newuser2");
        assertEquals(409, mockMvc.perform(post("/api/staff").content(duplicate).contentType(MediaType.APPLICATION_JSON).with(admin))
                .andReturn().getResponse().getStatus());
    }
}
