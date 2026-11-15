package com.hotel.workload.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-Department Assistance is a hotel-wide switch. Over HTTP, with real JWTs for real (rolled-back) accounts, for every role:
 *   - OFF: nobody gets new-support content: planning options, previews and confirmations are refused (409), and the support
 *     recommendations are empty with a note. Existing arrangements can still be listed, ended and archived by the roles allowed to.
 *   - ON again: each role gets back exactly the access it had before (the role rules themselves are never changed by the switch).
 *   - Staff: refused (403) whatever the switch says, as before.
 */
@SpringBootTest
@Transactional
public class CrossDepartmentRoleMatrixTest {

    private static final String DISABLED = "CROSS_DEPARTMENT_ASSISTANCE_DISABLED";
    private static final String OFF_NOTE = "Cross-department assistance is turned off in System Settings";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private JwtUtil jwt;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;
    private String admin;
    private User adminUser;
    private String manager;
    private String staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        admin = token("cdm_admin", Role.ADMIN);
        manager = token("cdm_mgr", Role.MANAGER);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).staffRecord("cdm_mgr" + fx.tag.toLowerCase(), Role.MANAGER, TestDepartments.HOUSEKEEPING);
        staff = token("cdm_staff", Role.STAFF);
        new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder).staffRecord("cdm_staff" + fx.tag.toLowerCase(), Role.STAFF, TestDepartments.HOUSEKEEPING);
    }

    private String token(String name, Role role) {
        User user = new User();
        user.setUsername(name + fx.tag.toLowerCase()); user.setEmail(name + fx.tag + "@t.test"); user.setPassword("x");
        user.setRole(role); user.setActive(true); user.setEmailVerified(true); user.setCredentialsSetupRequired(false);
        users.saveAndFlush(user);
        if (role == Role.ADMIN) adminUser = user;
        return "Bearer " + jwt.generateToken(user.getUsername(), role);
    }

    /** Sets the hotel-wide switch (the settings row is written inside this test's transaction, so it is rolled back). */
    private void crossDepartment(boolean enabled) {
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(true);
        row.setCapacityAlertEnabled(true);
        row.setCapacityThresholdPercent(85);
        row.setCrossDepartmentAssistanceEnabled(enabled);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        row.setUpdatedByUserId(adminUser.getId());
        row.setUpdatedByUsername(adminUser.getUsername());
        settingsRepository.saveAndFlush(row);
    }

    private ResultActions call(HttpMethod method, String url, String bearer, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).header("Authorization", bearer);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(b);
    }

    private static Object plan() {
        return Map.of("receivingDepartment", "HOUSEKEEPING", "items", List.of(Map.of("staffId", UUID.randomUUID().toString(), "taskId", UUID.randomUUID().toString())));
    }

    private static final String OPTIONS = "/api/support/options?receivingDepartment=HOUSEKEEPING";

    @Test
    @DisplayName("Admin: OFF refuses options, preview and confirm and empties the suggestions; ON restores all of it")
    void adminOffThenOn() throws Exception {
        crossDepartment(false);
        call(HttpMethod.GET, "/api/support/cross-department-status", admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.crossDepartmentAssistanceEnabled").value(false));
        call(HttpMethod.GET, OPTIONS, admin, null).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value(DISABLED));
        call(HttpMethod.POST, "/api/support/preview", admin, plan()).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value(DISABLED));
        call(HttpMethod.POST, "/api/support/assignments", admin, plan()).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value(DISABLED));
        call(HttpMethod.GET, "/api/workload/recommendations", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations").isEmpty()).andExpect(jsonPath("$.unsupported").isEmpty())
                .andExpect(jsonPath("$.limitations[0]").value(org.hamcrest.Matchers.startsWith(OFF_NOTE)));
        // existing arrangements stay manageable
        call(HttpMethod.GET, "/api/support/assignments", admin, null).andExpect(status().isOk());

        crossDepartment(true);
        call(HttpMethod.GET, "/api/support/cross-department-status", admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.crossDepartmentAssistanceEnabled").value(true));
        call(HttpMethod.GET, OPTIONS, admin, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/support/preview", admin, plan()).andExpect(status().isOk()).andExpect(jsonPath("$.problems[*].code", not(hasItem(DISABLED))));
        // confirming is back to the plan's own checks (this random plan is invalid, so it is still a 409, but not because of the switch)
        call(HttpMethod.POST, "/api/support/assignments", admin, plan()).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[*].code", not(hasItem(DISABLED))));
        call(HttpMethod.GET, "/api/workload/recommendations", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.limitations[*]", not(hasItem(org.hamcrest.Matchers.startsWith(OFF_NOTE)))));
    }

    @Test
    @DisplayName("Manager: OFF refuses options and preview and empties the suggestions; confirming stays Admin-only (403) either way; ON restores the rest")
    void managerOffThenOn() throws Exception {
        crossDepartment(false);
        call(HttpMethod.GET, "/api/support/cross-department-status", manager, null).andExpect(status().isOk()).andExpect(jsonPath("$.crossDepartmentAssistanceEnabled").value(false));
        call(HttpMethod.GET, OPTIONS, manager, null).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value(DISABLED));
        call(HttpMethod.POST, "/api/support/preview", manager, plan()).andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value(DISABLED));
        call(HttpMethod.POST, "/api/support/assignments", manager, plan()).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/workload/recommendations", manager, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations").isEmpty()).andExpect(jsonPath("$.limitations[0]").value(org.hamcrest.Matchers.startsWith(OFF_NOTE)));
        call(HttpMethod.GET, "/api/support/assignments", manager, null).andExpect(status().isOk());

        crossDepartment(true);
        call(HttpMethod.GET, OPTIONS, manager, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/support/preview", manager, plan()).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/support/assignments", manager, plan()).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/workload/recommendations", manager, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.limitations[*]", not(hasItem(org.hamcrest.Matchers.startsWith(OFF_NOTE)))));
    }

    @Test
    @DisplayName("Staff: refused every cross-department endpoint and the suggestions, whatever the switch says (nothing new is granted when it is ON)")
    void staffRefusedEitherWay() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            crossDepartment(enabled);
            call(HttpMethod.GET, "/api/support/cross-department-status", staff, null).andExpect(status().isForbidden());
            call(HttpMethod.GET, OPTIONS, staff, null).andExpect(status().isForbidden());
            call(HttpMethod.POST, "/api/support/preview", staff, plan()).andExpect(status().isForbidden());
            call(HttpMethod.POST, "/api/support/assignments", staff, plan()).andExpect(status().isForbidden());
            call(HttpMethod.GET, "/api/support/assignments", staff, null).andExpect(status().isForbidden());
            call(HttpMethod.POST, "/api/support/assignments/" + UUID.randomUUID() + "/end", staff, null).andExpect(status().isForbidden());
            call(HttpMethod.POST, "/api/support/assignments/" + UUID.randomUUID() + "/archive", staff, null).andExpect(status().isForbidden());
            call(HttpMethod.GET, "/api/workload/recommendations", staff, null).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("OFF: an existing arrangement stays ACTIVE and is ended normally by the Admin (the switch never blocks managing it)")
    void existingArrangementManagedWhileOff() throws Exception {
        var s = fx.overloaded(2, 2);
        crossDepartment(true);
        SupportTestFixture.signInAs("cdm_admin" + fx.tag.toLowerCase(), "ADMIN");
        SupportAssignmentDto created;
        try {
            created = context.getBean(SupportAssignmentService.class).confirm(fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0))).get(0);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        crossDepartment(false);
        org.junit.jupiter.api.Assertions.assertEquals(SupportStatus.ACTIVE, supportRepository.findById(created.id()).orElseThrow().getStatus());
        call(HttpMethod.GET, "/api/support/assignments", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(created.id().toString())));
        call(HttpMethod.POST, "/api/support/assignments/" + created.id() + "/end", admin, null).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertNotEquals(SupportStatus.ACTIVE, supportRepository.findById(created.id()).orElseThrow().getStatus());
    }
}
