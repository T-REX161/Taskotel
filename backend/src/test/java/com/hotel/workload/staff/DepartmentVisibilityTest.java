package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
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
 * Step 2I (C1): how much of each department {@code GET /api/departments} returns depends on who asks.
 * Admin: everything. Manager: everything for their own department, the basic identity (code, name, icon, colour, active) for the rest.
 * Staff: the basic identity only. Invalid Manager 403, anonymous 401. Every test rolls back.
 */
@SpringBootTest
@Transactional
public class DepartmentVisibilityTest {

    private static final String ALPHA = "DVT_ALPHA";
    private static final String BETA = "DVT_BETA";
    private static final List<String> RESTRICTED_FIELDS = List.of("description", "headStaffId", "headStaffName", "staffCount", "activeTasks", "status", "supervisor");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Department alpha, beta;

    @BeforeEach
    public void setUp() {
        // Start from an anonymous thread: another test class may have left a signed-in user in the security context
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(ALPHA);
        beta = fixtures.department(BETA);
        fixtures.account("dvt_admin", Role.ADMIN, true);
        fixtures.manager("dvt_m_alpha", alpha);
        fixtures.staff("dvt_s_alpha", alpha);
        Staff betaHead = fixtures.staff("dvt_s_beta", beta);
        beta.setDescription("Secret beta description");
        beta.setHeadStaff(betaHead);
        departmentRepository.saveAndFlush(beta);
        fixtures.account("dvt_m_nostaff", Role.MANAGER, true);

        Task task = new Task();
        task.setTitle("Beta work");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setDepartment(beta);
        task.setCreatedBy(userRepository.findByUsername("dvt_admin").orElseThrow());
        taskRepository.saveAndFlush(task);
    }

    private MvcResult list(String username, String role) throws Exception {
        return mockMvc.perform(get("/api/departments").with(user(username).roles(role))).andReturn();
    }

    private JsonNode department(MvcResult result, String code) throws Exception {
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            if (code.equals(node.get("code").asText())) return node;
        }
        fail("department " + code + " not in the response");
        return null;
    }

    private void assertBasicOnly(JsonNode node) {
        assertFalse(node.get("code").asText().isBlank());
        assertFalse(node.get("name").asText().isBlank());
        assertTrue(node.has("active") && node.has("iconKey") && node.has("colorKey"));
        for (String field : RESTRICTED_FIELDS) {
            assertTrue(!node.has(field) || node.get(field).isNull(), field + " must not be shown, was " + node.get(field));
        }
    }

    @Test
    @DisplayName("Admin receives every department in full, as before")
    public void adminSeesEverything() throws Exception {
        MvcResult result = list("dvt_admin", "ADMIN");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode beta = department(result, BETA);
        assertEquals("Secret beta description", beta.get("description").asText());
        assertEquals("Scope dvt_s_beta", beta.get("headStaffName").asText());
        assertEquals(1, beta.get("staffCount").asLong());
        assertEquals(1, beta.get("activeTasks").asLong());
        assertFalse(beta.get("status").isNull());
        assertEquals("Scope dvt_s_beta", beta.get("supervisor").asText());
        assertEquals(2, department(result, ALPHA).get("staffCount").asLong(), "manager and staff of alpha are both counted, as before");
    }

    @Test
    @DisplayName("A Manager sees their own department in full and every other department as basic identity only")
    public void managerSeesOwnInFullOthersBasic() throws Exception {
        MvcResult result = list("dvt_m_alpha", "MANAGER");
        assertEquals(200, result.getResponse().getStatus());

        JsonNode own = department(result, ALPHA);
        assertEquals(2, own.get("staffCount").asLong());
        assertFalse(own.get("status").isNull());
        assertFalse(own.get("supervisor").isNull());

        JsonNode other = department(result, BETA);
        assertBasicOnly(other);

        // Nothing about the other department leaks anywhere in the body, whatever the field
        String body = result.getResponse().getContentAsString();
        for (String hidden : List.of("Secret beta description", "dvt_s_beta", beta.getHeadStaff().getId().toString())) {
            assertFalse(body.contains(hidden), "leaked: " + hidden);
        }
        // Every department other than the Manager's own is basic
        List<String> notBasic = new ArrayList<>();
        for (JsonNode node : mapper.readTree(body)) {
            if (!ALPHA.equals(node.get("code").asText()) && !node.get("staffCount").isNull()) notBasic.add(node.get("code").asText());
        }
        assertTrue(notBasic.isEmpty(), "departments shown in full to a Manager of another department: " + notBasic);
    }

    @Test
    @DisplayName("Staff receive the basic identity of every department, including their own, and no figures or names")
    public void staffSeeBasicOnly() throws Exception {
        MvcResult result = list("dvt_s_alpha", "STAFF");
        assertEquals(200, result.getResponse().getStatus());
        assertBasicOnly(department(result, ALPHA));
        assertBasicOnly(department(result, BETA));
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            assertBasicOnly(node);
        }
    }

    @Test
    @DisplayName("The basic identity is enough for selectors: code, name, active flag, icon and colour")
    public void basicIdentityKeepsSelectorsWorking() throws Exception {
        JsonNode node = department(list("dvt_s_alpha", "STAFF"), BETA);
        assertEquals(BETA, node.get("code").asText());
        assertEquals(beta.getName(), node.get("name").asText());
        assertTrue(node.get("active").asBoolean());
        assertEquals(beta.getIconKey(), node.get("iconKey").asText());
        assertEquals(beta.getColorKey(), node.get("colorKey").asText());
    }

    @Test
    @DisplayName("An invalid Manager is refused (403) and nobody signed in gets 401")
    public void invalidManagerAndAnonymous() throws Exception {
        assertEquals(403, list("dvt_m_nostaff", "MANAGER").getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get("/api/departments")).andReturn().getResponse().getStatus());
    }
}
