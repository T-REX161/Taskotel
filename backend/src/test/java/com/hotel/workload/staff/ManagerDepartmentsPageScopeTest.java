package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Departments page: a Manager receives ONLY the departments they manage (their primary one and the active ones an Administrator
 * granted), in full, and no other department at all: no card, no name, no figure. Admin still receives every department and Staff the
 * basic identity of every department, exactly as before. The scope is read from the database on every request, so a revoked grant, an
 * inactive granted department and an inactive primary department show on the very next request.
 */
@SpringBootTest
@Transactional
public class ManagerDepartmentsPageScopeTest {

    private static final String HOME = "DPS_HOME";
    private static final String G1 = "DPS_GRANT_1";
    private static final String G2 = "DPS_GRANT_2";
    private static final String OTHER = "DPS_OTHER";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Department home;
    private Department g1;
    private Department g2;
    private Department other;
    private User manager;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        g1 = fixtures.department(G1);
        g2 = fixtures.department(G2);
        other = fixtures.department(OTHER);
        fixtures.account("dps_admin", Role.ADMIN, true);
        Staff head = fixtures.manager("dps_manager", home);
        manager = head.getUser();
        fixtures.staff("dps_home_staff", home);
        fixtures.staff("dps_g1_staff", g1);
        fixtures.staff("dps_g2_staff", g2);
        fixtures.staff("dps_other_staff", other);
        fixtures.staff("dps_staff_member", home);
    }

    private void grant(Department department) {
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(manager);
        grant.setDepartment(department);
        grant.setGrantedByUserId(java.util.UUID.randomUUID());
        grant.setGrantedByUsername("dps_admin");
        grantRepository.saveAndFlush(grant);
    }

    private void revoke(Department department) {
        grantRepository.findAll().stream()
                .filter(g -> g.getManager().getId().equals(manager.getId()) && g.getDepartment().getId().equals(department.getId()))
                .forEach(grantRepository::delete);
        grantRepository.flush();
    }

    private MvcResult list(String username, String role) throws Exception {
        return mockMvc.perform(get("/api/departments").with(user(username).roles(role))).andReturn();
    }

    private List<String> codes(MvcResult result) throws Exception {
        List<String> codes = new ArrayList<>();
        mapper.readTree(result.getResponse().getContentAsString()).forEach(d -> codes.add(d.get("code").asText()));
        return codes;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertNoTrace(String body, Department department) {
        assertFalse(body.contains(department.getCode()), "code of " + department.getCode() + " must be absent");
        assertFalse(body.contains(department.getName()), "name of " + department.getCode() + " must be absent");
    }

    @Test
    @DisplayName("Manager with one authorized department: exactly that department, in full; every other department is completely absent")
    public void oneAuthorizedDepartment() throws Exception {
        MvcResult result = list("dps_manager", "MANAGER");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(HOME), codes(result), "Total Departments is exactly 1");
        JsonNode card = body(result).get(0);
        assertTrue(card.get("managed").asBoolean());
        assertEquals(3, card.get("staffCount").asLong(), "the Manager, one staff member and the staff member");
        String json = result.getResponse().getContentAsString();
        for (Department hidden : List.of(g1, g2, other)) assertNoTrace(json, hidden);
        // not even the built-in departments appear
        assertFalse(json.contains("HOUSEKEEPING") || json.contains("FRONT_DESK") || json.contains("SECURITY"));
    }

    @Test
    @DisplayName("Manager with several authorized departments: exactly the primary and the granted ones, all in full, the rest absent")
    public void severalAuthorizedDepartments() throws Exception {
        grant(g1);
        grant(g2);
        MvcResult result = list("dps_manager", "MANAGER");
        assertEquals(java.util.Set.of(HOME, G1, G2), new java.util.HashSet<>(codes(result)));
        assertEquals(3, codes(result).size(), "Total Departments is exactly 3");
        long staffTotal = 0;
        for (JsonNode card : body(result)) {
            assertTrue(card.get("managed").asBoolean());
            assertFalse(card.get("staffCount").isNull(), "every authorized department is shown in full");
            staffTotal += card.get("staffCount").asLong();
        }
        assertEquals(3 + 1 + 1, staffTotal, "Active Staff covers only the authorized departments (home 3, grant 1 each, other 1 is NOT counted)");
        assertNoTrace(result.getResponse().getContentAsString(), other);
        // The Workload page's department list is the same authorized set
        MvcResult workload = mockMvc.perform(get("/api/workload/departments").with(user("dps_manager").roles("MANAGER"))).andReturn();
        assertEquals(new java.util.HashSet<>(codes(result)), new java.util.HashSet<>(codes(workload)));
    }

    @Test
    @DisplayName("A revoked grant disappears on the very next request, with no trace of the department")
    public void revokedGrant() throws Exception {
        grant(g1);
        assertTrue(codes(list("dps_manager", "MANAGER")).contains(G1));
        revoke(g1);
        MvcResult after = list("dps_manager", "MANAGER");
        assertEquals(List.of(HOME), codes(after));
        assertNoTrace(after.getResponse().getContentAsString(), g1);
    }

    @Test
    @DisplayName("An inactive granted department is not shown (the grant is kept) and comes back when it is active again")
    public void inactiveGrantedDepartment() throws Exception {
        grant(g1);
        grant(g2);
        g1.setActive(false);
        departmentRepository.saveAndFlush(g1);
        MvcResult inactive = list("dps_manager", "MANAGER");
        assertEquals(java.util.Set.of(HOME, G2), new java.util.HashSet<>(codes(inactive)));
        assertNoTrace(inactive.getResponse().getContentAsString(), g1);
        assertEquals(2, grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(manager.getId())).count(), "the grant itself is kept");
        g1.setActive(true);
        departmentRepository.saveAndFlush(g1);
        assertEquals(java.util.Set.of(HOME, G1, G2), new java.util.HashSet<>(codes(list("dps_manager", "MANAGER"))));
    }

    @Test
    @DisplayName("An inactive primary department denies the whole page (403), whatever grants remain")
    public void inactivePrimaryDepartment() throws Exception {
        grant(g1);
        home.setActive(false);
        departmentRepository.saveAndFlush(home);
        assertEquals(403, list("dps_manager", "MANAGER").getResponse().getStatus());
    }

    @Test
    @DisplayName("Admin still receives every department in full, with no managed flag")
    public void adminSeesAll() throws Exception {
        MvcResult result = list("dps_admin", "ADMIN");
        assertEquals(departmentRepository.count(), body(result).size());
        for (JsonNode card : body(result)) {
            assertFalse(card.has("managed"));
            assertFalse(card.get("staffCount").isNull());
        }
        assertTrue(codes(result).containsAll(List.of(HOME, G1, G2, OTHER)));
    }

    @Test
    @DisplayName("Staff are unchanged: the basic identity of every department, with no figures and no managed flag")
    public void staffUnchanged() throws Exception {
        MvcResult result = list("dps_staff_member", "STAFF");
        assertEquals(departmentRepository.count(), body(result).size());
        for (JsonNode card : body(result)) {
            assertFalse(card.has("managed"));
            assertTrue(card.get("staffCount").isNull(), "no figures for Staff");
        }
    }
}
