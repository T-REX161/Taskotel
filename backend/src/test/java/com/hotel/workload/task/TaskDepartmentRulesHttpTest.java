package com.hotel.workload.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The department rules the Create Task page and the Edit Task modal now reflect, checked through the real HTTP endpoints so the screens'
 * new locks are backed by the server: an Admin creates tasks in any active department; a Manager only in their own (another is 403, a
 * blank one means their own); a Manager with no department cannot create tasks; a Manager cannot move a task to another department.
 * Rolled back after each test.
 */
@SpringBootTest
@Transactional
public class TaskDepartmentRulesHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private Department mine;
    private Department other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        mine = fixtures.department("TDR_MINE");
        other = fixtures.department("TDR_OTHER");
        fixtures.account("tdr_admin", Role.ADMIN, true);
        fixtures.manager("tdr_manager", mine);
        fixtures.account("tdr_manager_nodept", Role.MANAGER, true);
    }

    private static String body(String title, String department, String status) {
        String dept = department == null ? "null" : "\"" + department + "\"";
        String st = status == null ? "" : ",\"status\":\"" + status + "\"";
        return "{\"title\":\"" + title + "\",\"category\":\"Front Desk\",\"priority\":\"MEDIUM\",\"estimatedDuration\":30,\"department\":" + dept + st + "}";
    }

    private MockHttpServletResponse create(String username, String role, String department) throws Exception {
        return mvc.perform(post("/api/tasks").with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)
                .content(body("TDR task", department, null))).andReturn().getResponse();
    }

    private String departmentOf(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString()).get("department").asText();
    }

    @Test
    @DisplayName("Admin can still create tasks in any active department")
    void adminCreatesInAnyActiveDepartment() throws Exception {
        for (Department department : new Department[] {mine, other}) {
            MockHttpServletResponse response = create("tdr_admin", "ADMIN", department.getCode());
            assertEquals(201, response.getStatus(), response.getContentAsString());
            assertEquals(department.getCode(), departmentOf(response));
        }
    }

    @Test
    @DisplayName("Manager creates in their own department; another department is refused (403) and nothing is saved")
    void managerOwnDepartmentOnly() throws Exception {
        MockHttpServletResponse own = create("tdr_manager", "MANAGER", mine.getCode());
        assertEquals(201, own.getStatus(), own.getContentAsString());
        assertEquals(mine.getCode(), departmentOf(own));

        MockHttpServletResponse elsewhere = create("tdr_manager", "MANAGER", other.getCode());
        assertEquals(403, elsewhere.getStatus());
        assertTrue(elsewhere.getContentAsString().contains("Managers can only create tasks in their own department."));
    }

    @Test
    @DisplayName("A Manager with no active department cannot create tasks (403, with the reason)")
    void managerWithoutDepartmentCannotCreate() throws Exception {
        MockHttpServletResponse response = create("tdr_manager_nodept", "MANAGER", mine.getCode());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("not assigned to an active department"), response.getContentAsString());
    }

    @Test
    @DisplayName("Edit: a Manager cannot move their task to another department (403); an Admin still can")
    void editDepartmentRules() throws Exception {
        MockHttpServletResponse created = create("tdr_manager", "MANAGER", mine.getCode());
        JsonNode task = mapper.readTree(created.getContentAsString());
        String id = task.get("id").asText();

        MockHttpServletResponse managerMove = mvc.perform(put("/api/tasks/" + id).with(user("tdr_manager").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body("TDR task", other.getCode(), "PENDING"))).andReturn().getResponse();
        assertEquals(403, managerMove.getStatus());
        assertTrue(managerMove.getContentAsString().contains("Managers cannot move a task to another department"));

        MockHttpServletResponse managerSameDept = mvc.perform(put("/api/tasks/" + id).with(user("tdr_manager").roles("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body("TDR task renamed", mine.getCode(), "PENDING"))).andReturn().getResponse();
        assertEquals(200, managerSameDept.getStatus(), managerSameDept.getContentAsString());

        MockHttpServletResponse adminMove = mvc.perform(put("/api/tasks/" + id).with(user("tdr_admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body("TDR task renamed", other.getCode(), "PENDING"))).andReturn().getResponse();
        assertEquals(200, adminMove.getStatus(), adminMove.getContentAsString());
        assertEquals(other.getCode(), departmentOf(adminMove));
    }
}
