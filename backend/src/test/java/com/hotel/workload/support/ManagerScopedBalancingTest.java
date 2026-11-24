package com.hotel.workload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * A Manager balances workload (cross-department support) only between departments they manage: the primary department plus the active
 * departments an Admin granted. The receiving department AND every lending (home) department must be in that set; an Admin is unrestricted.
 * Everything is checked on the server from database records, over HTTP with real JWTs.
 *
 * <p>Fixture: HOME (the Manager's primary) and GRANT (granted by the Admin) and OTHER and FAR (never granted). A receiving department is
 * one busy person plus two waiting tasks (so it needs helpers); a lending department is free, eligible staff with nothing waiting.
 */
@SpringBootTest
@Transactional
public class ManagerScopedBalancingTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;
    private Department home, grant, other, far;
    private User managerUser;
    private String admin, mgr, orphan;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        ScopeFixtures sf = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        home = fx.department("MS_HOME");
        grant = fx.department("MS_GRANT");
        other = fx.department("MS_OTHER");
        far = fx.department("MS_FAR");
        sf.account("ms_admin", Role.ADMIN, true);
        managerUser = sf.manager("ms_mgr", home).getUser();
        sf.account("ms_orphan", Role.MANAGER, true); // a Manager account with no Staff record
        admin = bearer("ms_admin", Role.ADMIN);
        mgr = bearer("ms_mgr", Role.MANAGER);
        orphan = bearer("ms_orphan", Role.MANAGER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String bearer(String username, Role role) {
        return "Bearer " + jwt.generateToken(username, role);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url).header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private int status(HttpMethod method, String url, String token, Object body) throws Exception {
        return call(method, url, token, body).getResponse().getStatus();
    }

    private void grant(Department... departments) throws Exception {
        List<String> codes = new ArrayList<>();
        for (Department d : departments) codes.add(d.getCode());
        assertEquals(200, status(HttpMethod.PUT, "/api/users/" + managerUser.getId() + "/view-departments", admin, Map.of("departments", codes)));
    }

    /** A receiving department: one busy person and two waiting tasks. Returns the waiting tasks. */
    private List<Task> overloaded(Department department) {
        fx.assign(fx.task("busy " + department.getCode(), department, Priority.MEDIUM, 40, com.hotel.workload.task.TaskStatus.PENDING),
                fx.eligible("Busy " + department.getCode(), department));
        return List.of(fx.waiting("w1 " + department.getCode(), department, Priority.URGENT, 30),
                fx.waiting("w2 " + department.getCode(), department, Priority.MEDIUM, 30));
    }

    /** A lending department: three free, eligible people. */
    private List<Staff> lenders(Department department) {
        List<Staff> people = new ArrayList<>();
        for (int i = 1; i <= 3; i++) people.add(fx.eligible("Lender " + i + " " + department.getCode(), department));
        return people;
    }

    private Map<String, Object> plan(Department receiving, Staff lender, Task task) {
        return Map.of("receivingDepartment", receiving.getCode(),
                "items", List.of(Map.of("staffId", lender.getId().toString(), "taskId", task.getId().toString())));
    }

    private static final String CONFIRM = "/api/support/assignments";

    // ── allowed ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Manager: balancing the primary department (receiving) from an authorized additional department succeeds and keeps the lender's home department")
    void primaryReceiverFromGrantedDonor() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(home);
        Staff lender = lenders(grant).get(0);
        assertEquals(200, status(HttpMethod.POST, "/api/support/preview", mgr, plan(home, lender, tasks.get(0))));
        assertEquals(201, status(HttpMethod.POST, CONFIRM, mgr, plan(home, lender, tasks.get(0))));
        assertEquals(grant.getCode(), staffRepository.findById(lender.getId()).orElseThrow().getDepartment().getCode(),
                "a temporary transfer never changes the permanent department");
    }

    @Test
    @DisplayName("Manager: an Admin-granted department can receive help from the primary department, and the reverse direction works too")
    void grantedReceiverFromPrimaryDonor() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(grant);
        assertEquals(201, status(HttpMethod.POST, CONFIRM, mgr, plan(grant, lenders(home).get(0), tasks.get(0))));
    }

    @Test
    @DisplayName("Manager: the options for an authorized receiver list only authorized donor departments")
    void optionsOfferOnlyAuthorizedDonors() throws Exception {
        grant(grant);
        overloaded(home);
        lenders(grant);
        lenders(other);
        JsonNode donors = mapper.readTree(call(HttpMethod.GET, "/api/support/options?receivingDepartment=" + home.getCode(), mgr, null)
                .getResponse().getContentAsString()).get("donors");
        List<String> codes = new ArrayList<>();
        donors.forEach(d -> codes.add(d.get("code").asText()));
        assertTrue(codes.contains(grant.getCode()), codes.toString());
        assertFalse(codes.contains(other.getCode()), "an unauthorized department is not offered: " + codes);
    }

    // ── blocked ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Manager: lending from an unauthorized department into an authorized one is rejected and creates nothing")
    void authorizedReceiverUnauthorizedDonor() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(home);
        long before = supportRepository.count();
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(home, lenders(other).get(0), tasks.get(0))));
        assertEquals(before, supportRepository.count());
    }

    @Test
    @DisplayName("Manager: lending from an authorized department into an unauthorized one is rejected, also by a direct request, and creates nothing")
    void unauthorizedReceiverAuthorizedDonor() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(other);
        Staff lender = lenders(home).get(0);
        long before = supportRepository.count();
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(other, lender, tasks.get(0))));
        assertEquals(404, status(HttpMethod.GET, "/api/support/options?receivingDepartment=" + other.getCode(), mgr, null));
        assertEquals(before, supportRepository.count());
    }

    @Test
    @DisplayName("Manager: a direct request between two unauthorized departments is rejected")
    void bothUnauthorized() throws Exception {
        List<Task> tasks = overloaded(other);
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(other, lenders(far).get(0), tasks.get(0))));
    }

    @Test
    @DisplayName("Manager: a missing Staff link fails closed")
    void missingStaffLinkFailsClosed() throws Exception {
        List<Task> tasks = overloaded(home);
        long before = supportRepository.count();
        assertEquals(403, status(HttpMethod.POST, CONFIRM, orphan, plan(home, lenders(grant).get(0), tasks.get(0))));
        assertEquals(before, supportRepository.count());
    }

    @Test
    @DisplayName("Manager: an inactive primary department fails closed")
    void inactivePrimaryFailsClosed() throws Exception {
        List<Task> tasks = overloaded(home);
        Staff lender = lenders(grant).get(0);
        home.setActive(false);
        departmentRepository.saveAndFlush(home);
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(home, lender, tasks.get(0))));
    }

    @Test
    @DisplayName("Manager: a revoked grant or an inactive additional department fails closed")
    void revokedOrInactiveGrantFailsClosed() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(grant);
        Staff lender = lenders(home).get(0);
        assertEquals(201, status(HttpMethod.POST, CONFIRM, mgr, plan(grant, lender, tasks.get(0))), "allowed while granted");

        grant(); // revoked
        List<Task> more = overloaded(grant);
        Staff lender2 = lenders(home).get(1);
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(grant, lender2, more.get(0))));

        grant(grant);
        grant.setActive(false); // deactivated after the grant
        departmentRepository.saveAndFlush(grant);
        assertEquals(403, status(HttpMethod.POST, CONFIRM, mgr, plan(grant, lender2, more.get(0))));
    }

    // ── unchanged ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin: confirms between any two departments, even ones no Manager manages")
    void adminUnrestricted() throws Exception {
        List<Task> tasks = overloaded(other);
        assertEquals(201, status(HttpMethod.POST, CONFIRM, admin, plan(other, lenders(far).get(0), tasks.get(0))));
    }

    @Test
    @DisplayName("Cross-Department Support switch OFF still refuses a permitted Manager plan (409), as before")
    void switchOffStillRefuses() throws Exception {
        grant(grant);
        List<Task> tasks = overloaded(home);
        Staff lender = lenders(grant).get(0);
        JsonNode s = mapper.readTree(call(HttpMethod.GET, "/api/system-settings", admin, null).getResponse().getContentAsString());
        assertEquals(200, status(HttpMethod.PUT, "/api/system-settings", admin, Map.of(
                "autoBalancingEnabled", s.get("autoBalancingEnabled").asBoolean(), "capacityAlertEnabled", s.get("capacityAlertEnabled").asBoolean(),
                "crossDepartmentAssistanceEnabled", false, "capacityThresholdPercent", s.get("capacityThresholdPercent").asInt())));
        assertEquals(409, status(HttpMethod.POST, CONFIRM, mgr, plan(home, lender, tasks.get(0))));
    }

    @Test
    @DisplayName("A Manager still cannot change a Staff member's permanent department")
    void permanentDepartmentChangeStaysAdminOnly() throws Exception {
        Staff person = fx.eligible("Permanent Pat", home);
        int status = status(HttpMethod.PUT, "/api/staff/" + person.getId(), mgr,
                Map.of("name", person.getName(), "department", far.getCode()));
        assertTrue(status == 403 || status == 400 || status == 404, "refused, was " + status);
        assertEquals(home.getCode(), staffRepository.findById(person.getId()).orElseThrow().getDepartment().getCode());
    }
}
