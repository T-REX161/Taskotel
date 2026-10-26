package com.hotel.workload.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real application (Hibernate with {@code ddl-auto=validate}, PostgreSQL, the real controllers and services)
 * against the rehearsal COPY of the real database after V4 has been applied by {@link DepartmentMigrationIT}.
 *
 * <p>Run after that test, with the same stamp:
 * {@code mvn -Pmigration-it test -Dtest=DepartmentApplicationIT}. Every test runs in a transaction that is rolled back,
 * so the copy is left exactly as the migration test left it. Only a {@code wb_rehearsal_*_copy} database is accepted
 * (same guard as the other migration tests); the live database can never be targeted.
 */
@Tag("pg-migration")
@SpringBootTest
@Transactional
public class DepartmentApplicationIT {

    @DynamicPropertySource
    static void rehearsalCopy(DynamicPropertyRegistry registry) {
        String stamp = System.getenv("WB_MIGRATION_TEST_STAMP");
        String password = System.getenv("DB_PASSWORD");
        if (stamp == null || stamp.isBlank() || password == null || password.isBlank()) {
            fail("WB_MIGRATION_TEST_STAMP and DB_PASSWORD must be set to run the PostgreSQL migration rehearsal tests");
        }
        String database = "wb_rehearsal_" + stamp + "_copy";
        PostgresMigrationIT.assertRehearsalDatabaseName(database); // never the live database
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + database);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USER", "postgres"));
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // The setting production uses: the mapping must match the schema V4 produced, or the application will not start
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Long> countsBy(String sql) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql, rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        });
        return counts;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("The application starts with ddl-auto=validate on the migrated copy, and /api/departments returns the six departments with the real staff and task counts")
    public void departmentsEndpointOnRealData() throws Exception {
        assertEquals("wb_rehearsal_" + System.getenv("WB_MIGRATION_TEST_STAMP") + "_copy", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("4", jdbc.queryForObject("SELECT max(version::int)::text FROM flyway_schema_history", String.class), "The copy is at V4");

        Map<String, Long> activeStaff = countsBy("SELECT department, count(*) FROM staff WHERE active GROUP BY department");
        Map<String, Long> activeTasks = countsBy("SELECT department, count(*) FROM tasks WHERE status IN ('PENDING','ASSIGNED','IN_PROGRESS') GROUP BY department");

        JsonNode departments = json(mockMvc.perform(get("/api/departments")).andExpect(status().isOk()).andReturn());
        assertEquals(6, departments.size());
        List<String> codes = new ArrayList<>();
        for (JsonNode department : departments) {
            String code = department.get("code").asText();
            codes.add(code);
            assertEquals(activeStaff.getOrDefault(code, 0L), department.get("staffCount").asLong(), code + " staff count");
            assertEquals(activeTasks.getOrDefault(code, 0L), department.get("activeTasks").asLong(), code + " active task count");
            assertTrue(department.get("active").asBoolean());
            System.out.println("DEPARTMENT " + code + ": staff=" + department.get("staffCount").asLong() + " activeTasks=" + department.get("activeTasks").asLong()
                    + " icon=" + department.get("iconKey").asText() + " color=" + department.get("colorKey").asText());
        }
        assertEquals(List.of("HOUSEKEEPING", "FRONT_DESK", "MAINTENANCE", "ROOM_SERVICE", "FOOD_AND_BEVERAGE", "SECURITY"), codes);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Every real staff member and task is returned with the same department code the database stores, and a department name")
    public void staffAndTasksKeepTheirDepartments() throws Exception {
        Map<String, String> staffDepartment = new HashMap<>();
        jdbc.query("SELECT email, department FROM staff", rs -> {
            staffDepartment.put(rs.getString(1), rs.getString(2));
        });
        JsonNode staff = json(mockMvc.perform(get("/api/staff")).andExpect(status().isOk()).andReturn());
        assertEquals(staffDepartment.size(), staff.size(), "All real staff are returned");
        for (JsonNode member : staff) {
            assertEquals(staffDepartment.get(member.get("email").asText()), member.get("department").asText());
            assertFalse(member.get("departmentName").asText().isBlank());
        }

        Map<String, String> taskDepartment = new HashMap<>();
        jdbc.query("SELECT id::text, coalesce(department, '<null>') FROM tasks", rs -> {
            taskDepartment.put(rs.getString(1), rs.getString(2));
        });
        JsonNode tasks = json(mockMvc.perform(get("/api/tasks")).andExpect(status().isOk()).andReturn());
        assertEquals(taskDepartment.size(), tasks.size(), "All real tasks are returned");
        for (JsonNode task : tasks) {
            String stored = taskDepartment.get(task.get("id").asText());
            assertEquals(stored, task.hasNonNull("department") ? task.get("department").asText() : "<null>");
        }

        JsonNode workload = json(mockMvc.perform(get("/api/workload")).andExpect(status().isOk()).andReturn());
        assertEquals(staffDepartment.size(), workload.size(), "Workload covers every real staff member");
        for (JsonNode entry : workload) {
            assertNotNull(entry.get("departmentName"));
        }
        System.out.println("REAL DATA OK: " + staff.size() + " staff, " + tasks.size() + " tasks, " + workload.size() + " workload rows");
    }

    @Test
    @WithMockUser(username = "rehearsal_admin", roles = "ADMIN")
    @DisplayName("On the real data: create a department, then staff, a task, an assignment and see its workload (rolled back afterwards)")
    public void createDepartmentAndUseItOnRealData() throws Exception {
        User admin = new User();
        admin.setUsername("rehearsal_admin");
        admin.setEmail("rehearsal_admin@example.invalid");
        admin.setPassword(passwordEncoder.encode("password"));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        userRepository.saveAndFlush(admin);

        MvcResult created = mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Spa & Wellness\", \"description\": \"Rehearsal\", \"iconKey\": \"users\", \"colorKey\": \"rose\"}"))
                .andExpect(status().isCreated()).andReturn();
        String code = json(created).get("code").asText();
        assertEquals("SPA_AND_WELLNESS", code);
        assertEquals(7, json(mockMvc.perform(get("/api/departments")).andReturn()).size());

        assertEquals(409, mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"  spa & wellness \"}")).andReturn().getResponse().getStatus(), "Duplicate name");
        assertEquals(409, mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"HOUSEKEEPING\"}")).andReturn().getResponse().getStatus(), "Duplicate of a built-in department");

        MvcResult staff = mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(
                "{\"fullName\": \"Rehearsal Spa\", \"email\": \"rehearsal_spa@example.invalid\", \"phone\": \"555-0100\", \"department\": \"" + code
                        + "\", \"availability\": \"AVAILABLE\", \"username\": \"rehearsal_spa\", \"temporaryPassword\": \"TempPass@123\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID staffId = UUID.fromString(json(staff).get("id").asText());
        MvcResult task = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(
                "{\"title\": \"Rehearsal task\", \"category\": \"General\", \"priority\": \"MEDIUM\", \"estimatedDuration\": 75, \"dueDate\": \"2026-12-31\", \"department\": \"" + code + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID taskId = UUID.fromString(json(task).get("id").asText());
        mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\": \"" + taskId + "\", \"staffId\": \"" + staffId + "\"}")).andExpect(status().isCreated());

        boolean found = false;
        for (JsonNode entry : json(mockMvc.perform(get("/api/workload")).andReturn())) {
            if (staffId.toString().equals(entry.get("staffId").asText())) {
                found = true;
                assertEquals(code, entry.get("department").asText());
                assertEquals(75, entry.get("totalEstimatedDuration").asInt());
                assertEquals(1, entry.get("activeTaskCount").asInt());
            }
        }
        assertTrue(found);
        for (JsonNode department : json(mockMvc.perform(get("/api/departments")).andReturn())) {
            if (code.equals(department.get("code").asText())) {
                assertEquals(1, department.get("staffCount").asInt());
                assertEquals(1, department.get("activeTasks").asInt());
            }
        }
        // The original six are untouched by all of this
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM departments WHERE code <> '" + code + "'", Integer.class));
        System.out.println("CREATE + USE OK for department " + code);
    }

    @Test
    @WithMockUser(username = "rehearsal_admin", roles = "ADMIN")
    @DisplayName("On the real data: DELETE /api/departments/{code} removes an unused department and refuses built-in or in-use ones (rolled back afterwards)")
    public void removeDepartmentOnRealData() throws Exception {
        User admin = new User();
        admin.setUsername("rehearsal_admin");
        admin.setEmail("rehearsal_admin@example.invalid");
        admin.setPassword(passwordEncoder.encode("password"));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        userRepository.saveAndFlush(admin);
        long staffBefore = jdbc.queryForObject("SELECT count(*) FROM staff", Long.class);
        long tasksBefore = jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class);
        long assignmentsBefore = jdbc.queryForObject("SELECT count(*) FROM assignments", Long.class);

        // An unused department is removed, and the list no longer has it
        String valet = json(mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Valet Parking\"}"))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        assertEquals(204, mockMvc.perform(delete("/api/departments/" + valet)).andReturn().getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = ?", Integer.class, valet));
        assertEquals(6, json(mockMvc.perform(get("/api/departments")).andReturn()).size());
        assertEquals(404, mockMvc.perform(delete("/api/departments/" + valet)).andReturn().getResponse().getStatus(), "Already removed");

        // A built-in department with real staff is refused and untouched
        MvcResult builtIn = mockMvc.perform(delete("/api/departments/MAINTENANCE")).andReturn();
        assertEquals(409, builtIn.getResponse().getStatus());
        assertTrue(json(builtIn).get("message").asText().contains("built-in"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = 'MAINTENANCE'", Integer.class));

        // A custom department with staff, a task and an assignment is refused, naming every blocker
        String pool = json(mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Pool Deck\"}"))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        UUID staffId = UUID.fromString(json(mockMvc.perform(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(
                "{\"fullName\": \"Rehearsal Lifeguard\", \"email\": \"rehearsal_pool@example.invalid\", \"phone\": \"555-0100\", \"department\": \"" + pool
                        + "\", \"availability\": \"AVAILABLE\", \"username\": \"rehearsal_pool\", \"temporaryPassword\": \"TempPass@123\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        UUID taskId = UUID.fromString(json(mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(
                "{\"title\": \"Check pool chemistry\", \"category\": \"General\", \"priority\": \"MEDIUM\", \"estimatedDuration\": 20, \"dueDate\": \"2026-12-31\", \"department\": \"" + pool + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\": \"" + taskId + "\", \"staffId\": \"" + staffId + "\"}")).andExpect(status().isCreated());
        MvcResult inUse = mockMvc.perform(delete("/api/departments/" + pool)).andReturn();
        assertEquals(409, inUse.getResponse().getStatus());
        assertEquals("\"Pool Deck\" cannot be removed because it still has 1 staff member, 1 task and 1 assignment. "
                + "Move them to another department first, or mark the department Inactive.", json(inUse).get("message").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = ?", Integer.class, pool));
        assertEquals(staffBefore + 1, jdbc.queryForObject("SELECT count(*) FROM staff", Long.class), "Nothing was deleted with it");
        assertEquals(tasksBefore + 1, jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class));
        assertEquals(assignmentsBefore + 1, jdbc.queryForObject("SELECT count(*) FROM assignments", Long.class));
        System.out.println("REMOVE OK: unused removed (204), built-in refused (409), in-use refused (409): " + json(inUse).get("message").asText());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("Authorization on the real stack: staff can read departments but not create or remove them")
    public void staffRoleIsReadOnly() throws Exception {
        mockMvc.perform(get("/api/departments")).andExpect(status().isOk());
        mockMvc.perform(post("/api/departments").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/departments/SECURITY")).andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = 'SECURITY'", Integer.class));
    }

    @Test
    @DisplayName("Removing a department without signing in is refused (401) on the real stack")
    public void removeRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/departments/SECURITY")).andExpect(status().isUnauthorized());
    }
}
