package com.hotel.workload.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The real seeder, switched on, against an empty database of its own; then the five demo accounts sign in through the real login endpoint
 * (no email service involved) and use the API with the real JWT.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.demo-data.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:demoseedtestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL"
})
public class DemoAccountsIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @MockitoBean private EmailService emailService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private static int clients = 0;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MvcResult login(String username) throws Exception {
        String client = "10.99.0." + (++clients);
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"password\"}").with(r -> { r.setRemoteAddr(client); return r; })).andReturn();
    }

    private String tokenFor(String username) throws Exception {
        MvcResult result = login(username);
        assertEquals(200, result.getResponse().getStatus(), username + ": " + result.getResponse().getContentAsString());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertNotNull(body.get("token"), username + " gets a session token, not a verification request: " + body);
        return body.get("token").asText();
    }

    private JsonNode api(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(200, result.getResponse().getStatus(), path + ": " + result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> field(JsonNode array, String name) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.get(name).asText()));
        values.sort(String::compareTo);
        return values;
    }

    // ── the seeded data ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The five accounts exist with their roles; all are active, email-verified and need no first-password setup")
    void accountsAreReady() {
        Map<String, Role> expected = Map.of("admin", Role.ADMIN, "manager", Role.MANAGER, "rahul", Role.STAFF, "priya", Role.STAFF, "amit", Role.STAFF);
        assertEquals(5, userRepository.count());
        expected.forEach((name, role) -> {
            User user = userRepository.findByUsername(name).orElseThrow(() -> new AssertionError(name + " missing"));
            assertEquals(role, user.getRole(), name);
            assertTrue(user.isActive() && user.isEmailVerified() && !user.isCredentialsSetupRequired(), name);
            assertNotNull(user.getId(), name + " has a UUID id");
        });
    }

    @Test
    @DisplayName("The Manager and the three Staff each have a linked, active Staff record in an active department; the Admin has none")
    void staffRecordsAreLinked() {
        Map<String, String> departments = Map.of("manager", "HOUSEKEEPING", "rahul", "HOUSEKEEPING", "priya", "FRONT_DESK", "amit", "MAINTENANCE");
        departments.forEach((name, code) -> {
            Staff staff = staffRepository.findByUserUsername(name).orElseThrow(() -> new AssertionError(name + " has no staff record"));
            assertTrue(staff.isActive(), name);
            assertNotNull(staff.getDepartment(), name);
            assertTrue(staff.getDepartment().isActive(), name);
            assertEquals(code, staff.getDepartment().getCode(), name);
            assertEquals(userRepository.findByUsername(name).orElseThrow().getEmail(), staff.getEmail(), name + ": the staff email matches the login email");
        });
        assertTrue(staffRepository.findByUserUsername("admin").isEmpty());
    }

    // ── signing in ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All five accounts sign in with the demo password and a session token, and no email is requested")
    void allFiveSignIn() throws Exception {
        for (String name : List.of("admin", "manager", "rahul", "priya", "amit")) tokenFor(name);
        verify(emailService, never()).sendVerificationEmail(any(), any());
    }

    // ── what each account can reach ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin keeps global access: every task, every staff record, the dashboard and the workload of all operational staff")
    void adminSeesEverything() throws Exception {
        String token = tokenFor("admin");
        assertEquals(List.of("AC Maintenance", "Front Desk Assistance", "Room Cleaning", "Room Inspection"), field(api(token, "/api/tasks"), "title"));
        assertEquals(4, api(token, "/api/staff").size(), "the three Staff and the Manager's record");
        assertEquals(List.of("Amit", "Priya", "Rahul"), field(api(token, "/api/workload"), "staffName"));
        assertEquals(3, api(token, "/api/dashboard/summary").get("totalStaff").asInt());
        assertEquals(4, api(token, "/api/dashboard/summary").get("totalTasks").asInt());
    }

    @Test
    @DisplayName("The Manager works in Housekeeping only: its tasks, its Staff, its workload and its dashboard, and nothing of the other departments")
    void managerSeesOwnDepartment() throws Exception {
        String token = tokenFor("manager");
        assertEquals(List.of("Room Cleaning", "Room Inspection"), field(api(token, "/api/tasks"), "title"));
        assertEquals(List.of("Rahul"), field(api(token, "/api/staff"), "name"));
        assertEquals(List.of("Rahul"), field(api(token, "/api/workload"), "staffName"));
        JsonNode summary = api(token, "/api/dashboard/summary");
        assertEquals(2, summary.get("totalTasks").asInt());
        assertEquals(1, summary.get("totalStaff").asInt());
        assertEquals(2, api(token, "/api/assignments").size());
    }

    @Test
    @DisplayName("Rahul sees his own two tasks, his own workload row and his own dashboard")
    void rahulSeesOwnWork() throws Exception {
        String token = tokenFor("rahul");
        assertEquals(List.of("Room Cleaning", "Room Inspection"), field(api(token, "/api/tasks"), "title"));
        JsonNode workload = api(token, "/api/workload");
        assertEquals(List.of("Rahul"), field(workload, "staffName"));
        assertEquals(2, workload.get(0).get("activeTaskCount").asInt());
        assertEquals(60, workload.get(0).get("totalEstimatedDuration").asInt());
        assertEquals(2, api(token, "/api/dashboard/summary").get("recentAssignments").size());
    }

    @Test
    @DisplayName("Priya sees her one task and workload; Amit, who is unavailable and has no task, gets empty lists and a workload row of his own")
    void priyaAndAmit() throws Exception {
        String priya = tokenFor("priya");
        assertEquals(List.of("Front Desk Assistance"), field(api(priya, "/api/tasks"), "title"));
        assertEquals(List.of("Priya"), field(api(priya, "/api/workload"), "staffName"));
        assertEquals(45, api(priya, "/api/workload").get(0).get("totalEstimatedDuration").asInt());
        api(priya, "/api/dashboard/summary");

        String amit = tokenFor("amit");
        assertEquals(0, api(amit, "/api/tasks").size());
        JsonNode workload = api(amit, "/api/workload");
        assertEquals(List.of("Amit"), field(workload, "staffName"));
        assertEquals(0, workload.get(0).get("activeTaskCount").asInt());
        assertEquals("UNAVAILABLE", workload.get(0).get("availability").asText());
        api(amit, "/api/dashboard/summary");
    }

    @Test
    @DisplayName("Staff and the Manager are still refused what their role does not allow (the seed changed no permission)")
    void permissionsUnchanged() throws Exception {
        String rahul = tokenFor("rahul");
        assertEquals(403, mockMvc.perform(get("/api/staff").header("Authorization", "Bearer " + rahul)).andReturn().getResponse().getStatus());
        String manager = tokenFor("manager");
        assertEquals(403, mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + manager)).andReturn().getResponse().getStatus());
    }
}
