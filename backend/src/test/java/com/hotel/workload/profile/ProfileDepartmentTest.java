package com.hotel.workload.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/profile carries the department of the caller's OWN staff record (departmentCode / departmentName), only while that department
 * is active, and null otherwise. Real security chain and H2; rolled back after each test.
 */
@SpringBootTest
@Transactional
public class ProfileDepartmentTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private ScopeFixtures fixtures;
    private Department frontDesk;
    private Department other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        frontDesk = fixtures.department("PDEP_FD");
        other = fixtures.department("PDEP_OTHER");
    }

    private JsonNode profile(String username, Role role) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/profile").with(user(username).roles(role.name()))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }

    private static void assertDepartment(JsonNode body, String code, String name) {
        assertTrue(body.has("departmentCode") && body.has("departmentName"), "both fields are always present");
        if (code == null) {
            assertTrue(body.get("departmentCode").isNull(), "departmentCode is null");
            assertTrue(body.get("departmentName").isNull(), "departmentName is null");
        } else {
            assertEquals(code, body.get("departmentCode").asText());
            assertEquals(name, body.get("departmentName").asText());
        }
    }

    @Test
    @DisplayName("Manager, Staff and Admin with a staff record in an active department get that department")
    void eachRoleGetsItsOwnActiveDepartment() throws Exception {
        fixtures.manager("pdep_manager", frontDesk);
        fixtures.staff("pdep_staff", frontDesk);
        fixtures.staffRecord("pdep_admin", Role.ADMIN, other);
        assertDepartment(profile("pdep_manager", Role.MANAGER), frontDesk.getCode(), frontDesk.getName());
        assertDepartment(profile("pdep_staff", Role.STAFF), frontDesk.getCode(), frontDesk.getName());
        assertDepartment(profile("pdep_admin", Role.ADMIN), other.getCode(), other.getName());
    }

    @Test
    @DisplayName("No staff record (the usual Admin shape, or a Manager account without one): both fields null")
    void noStaffRecordMeansNull() throws Exception {
        fixtures.account("pdep_admin_only", Role.ADMIN, true);
        fixtures.account("pdep_manager_only", Role.MANAGER, true);
        assertDepartment(profile("pdep_admin_only", Role.ADMIN), null, null);
        assertDepartment(profile("pdep_manager_only", Role.MANAGER), null, null);
    }

    @Test
    @DisplayName("A staff record with no department: both fields null, for every role")
    void noDepartmentMeansNull() throws Exception {
        for (Role role : Role.values()) {
            String username = "pdep_nodept_" + role.name().toLowerCase();
            fixtures.staffRecord(username, role, null);
            assertDepartment(profile(username, role), null, null);
        }
    }

    @Test
    @DisplayName("An inactive department is not reported: both fields null")
    void inactiveDepartmentMeansNull() throws Exception {
        Department closed = fixtures.department("PDEP_CLOSED");
        fixtures.manager("pdep_manager_closed", closed);
        fixtures.staff("pdep_staff_closed", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);
        assertDepartment(profile("pdep_manager_closed", Role.MANAGER), null, null);
        assertDepartment(profile("pdep_staff_closed", Role.STAFF), null, null);
    }

    @Test
    @DisplayName("Only the caller's own department is returned, never another user's")
    void neverAnotherUsersDepartment() throws Exception {
        fixtures.manager("pdep_mgr_a", frontDesk);
        Staff colleague = fixtures.manager("pdep_mgr_b", other);
        JsonNode mine = profile("pdep_mgr_a", Role.MANAGER);
        assertDepartment(mine, frontDesk.getCode(), frontDesk.getName());
        String raw = mine.toString();
        assertFalse(raw.contains(other.getCode()), "another department's code is not in the response");
        assertFalse(raw.contains(colleague.getUser().getUsername()), "another user is not in the response");
    }
}
