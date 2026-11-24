package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.ScopeFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Regression: Staff Management lists STAFF-role accounts only. An Admin or Manager account has a linked Staff record (a Manager's primary
 * department lives there) but is never a staff member in the list, the department staff figure or the capacity inputs.
 */
@SpringBootTest
class StaffManagementRoleOnlyTest {

    private static final String[] USERS = {"sro_admin", "sro_manager", "sro_staff", "sro_staff_inactive"};

    @MockitoBean private com.hotel.workload.verification.EmailService emailService;

    @Autowired private WebApplicationContext context;
    @Autowired private StaffRepository staffRepository;
    @Autowired private com.hotel.workload.security.UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private ScopeFixtures fixtures;
    private MockMvc mockMvc;
    private Department department;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        fixtures.remove(USERS);
        department = fixtures.department("FRONT_DESK");
        // An Admin and a Manager, each with a linked Staff record in the department, beside an ordinary Staff member
        fixtures.staffRecord("sro_admin", com.hotel.workload.security.Role.ADMIN, department);
        fixtures.manager("sro_manager", department);
        fixtures.staff("sro_staff", department);
    }

    @AfterEach
    void cleanUp() {
        fixtures.remove(USERS);
    }

    private List<String> usernames(String url) throws Exception {
        JsonNode body = json.readTree(mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString());
        List<String> names = new ArrayList<>();
        body.forEach(row -> names.add(row.get("username").asText()));
        return names;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin: the Staff Management listing holds STAFF accounts only, never ADMIN or MANAGER")
    void adminListingIsStaffOnly() throws Exception {
        List<String> names = usernames("/api/staff");
        assertTrue(names.contains("sro_staff"));
        assertFalse(names.contains("sro_admin"), "an Admin account is not a staff member");
        assertFalse(names.contains("sro_manager"), "a Manager account is not a staff member");
        // Every row is a STAFF account
        JsonNode body = json.readTree(mockMvc.perform(get("/api/staff")).andReturn().getResponse().getContentAsString());
        body.forEach(row -> assertEquals("STAFF", row.get("role").asText()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin: the Users page can still ask for the Manager and Admin Staff records (department linking is untouched)")
    void adminCanOptInToLinkedRecords() throws Exception {
        List<String> names = usernames("/api/staff?includePrivileged=true");
        assertTrue(names.containsAll(List.of("sro_admin", "sro_manager", "sro_staff")));
        // The records are still there: nothing was removed or re-roled to hide them
        assertTrue(staffRepository.findByUserUsername("sro_manager").isPresent());
        assertTrue(staffRepository.findByUserUsername("sro_admin").isPresent());
    }

    @Test
    @WithMockUser(username = "sro_manager", roles = "MANAGER")
    @DisplayName("Manager: sees STAFF of their department only, and the opt-in changes nothing for them")
    void managerListingIsStaffOnly() throws Exception {
        for (String url : List.of("/api/staff", "/api/staff?includePrivileged=true")) {
            List<String> names = usernames(url);
            assertTrue(names.contains("sro_staff"), url);
            assertFalse(names.contains("sro_manager"), url);
            assertFalse(names.contains("sro_admin"), url);
        }
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("Staff: still refused")
    void staffStillRefused() throws Exception {
        assertEquals(403, mockMvc.perform(get("/api/staff")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("Department staff figure and active-staff counts exclude Manager and Admin records")
    void departmentCountsExcludePrivilegedRecords() {
        long staffOnly = staffRepository.countActiveStaffRoleInDepartment(department);
        long expected = staffRepository.findByDepartmentAndUserRole(department, com.hotel.workload.security.Role.STAFF).stream().filter(Staff::isActive).count();
        assertEquals(expected, staffOnly, "only STAFF-role accounts count");
        assertTrue(staffOnly >= 1);
        assertTrue(staffRepository.countByDepartmentAndActiveTrue(department) >= staffOnly + 2, "the Admin and Manager records exist but are not counted");
    }

    @Test
    @DisplayName("Capacity inputs count STAFF accounts only")
    void capacityCountsStaffOnly() {
        long staffRows = staffRepository.countCapacityStaffByDepartmentCode().stream()
                .filter(row -> department.getCode().equals(row[0])).mapToLong(row -> ((Number) row[1]).longValue()).sum();
        long operational = staffRepository.countOperationalInDepartment(department);
        assertTrue(operational >= 1);
        assertTrue(staffRows <= operational, "available STAFF cannot exceed STAFF, so no Manager/Admin record is counted");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("Department head: the Admin's picker source offers Manager and Admin records, and the head rule still accepts them")
    void departmentHeadCanStillBeAPrivilegedRecord() throws Exception {
        UUID managerRecord = staffRepository.findByUserUsername("sro_manager").orElseThrow().getId();
        // The normal listing hides the record; the head picker's opt-in lists it
        assertFalse(json.readTree(mockMvc.perform(get("/api/staff")).andReturn().getResponse().getContentAsString()).toString().contains(managerRecord.toString()));
        assertTrue(json.readTree(mockMvc.perform(get("/api/staff?includePrivileged=true")).andReturn().getResponse().getContentAsString()).toString().contains(managerRecord.toString()));
        // Existing rule (any active staff record): unchanged, so the picker's choice is accepted by the server
        var created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/departments")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Sro Head Check\", \"headStaffId\": \"" + managerRecord + "\"}")).andReturn().getResponse();
        assertEquals(201, created.getStatus(), created.getContentAsString());
        assertEquals(managerRecord.toString(), json.readTree(created.getContentAsString()).get("headStaffId").asText());
        // Leave nothing pointing at the fixture records, so their clean-up can remove them
        Department made = departmentRepository.findAll().stream().filter(d -> "Sro Head Check".equals(d.getName())).findFirst().orElseThrow();
        departmentRepository.delete(made);
        departmentRepository.flush();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("Admin and Manager Staff records are privileged and skipped by the recommendation and support tallies")
    void privilegedRecordsAreFlagged() {
        assertTrue(staffRepository.findByUserUsername("sro_admin").orElseThrow().isPrivilegedAccount());
        assertTrue(staffRepository.findByUserUsername("sro_manager").orElseThrow().isPrivilegedAccount());
        assertFalse(staffRepository.findByUserUsername("sro_staff").orElseThrow().isPrivilegedAccount());
    }
}
