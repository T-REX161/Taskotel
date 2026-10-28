package com.hotel.workload.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may preview, confirm and read cross-department support, with real JWTs for real (rolled-back) accounts. */
@SpringBootTest
@Transactional
public class SupportAuthorizationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private JwtUtil jwt;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private SupportTestFixture fx;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
    }

    private String token(String name, Role role, boolean active) {
        User user = new User();
        user.setUsername(name + fx.tag.toLowerCase()); user.setEmail(name + fx.tag + "@t.test"); user.setPassword("x");
        user.setRole(role); user.setActive(active); user.setEmailVerified(true); user.setCredentialsSetupRequired(false);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateToken(user.getUsername(), role);
    }

    private MockHttpServletRequestBuilder req(HttpMethod method, String url, String bearer, Object body) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url);
        if (bearer != null) b.header("Authorization", bearer);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return b;
    }

    private Object randomPlan() {
        return java.util.Map.of("receivingDepartment", "HOUSEKEEPING",
                "items", java.util.List.of(java.util.Map.of("staffId", UUID.randomUUID().toString(), "taskId", UUID.randomUUID().toString())));
    }

    @Test
    @DisplayName("Admin and Manager can read options, preview, confirm and read the history; a bad plan is a 409 with the problems")
    void adminAndManagerAreAuthorized() throws Exception {
        for (String bearer : new String[]{token("sup_admin", Role.ADMIN, true), token("sup_mgr", Role.MANAGER, true)}) {
            mvc.perform(req(HttpMethod.GET, "/api/support/options?receivingDepartment=HOUSEKEEPING", bearer, null)).andExpect(status().isOk());
            mvc.perform(req(HttpMethod.HEAD, "/api/support/options?receivingDepartment=HOUSEKEEPING", bearer, null)).andExpect(status().isOk());
            mvc.perform(req(HttpMethod.POST, "/api/support/preview", bearer, randomPlan()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.problems").isNotEmpty());
            mvc.perform(req(HttpMethod.POST, "/api/support/assignments", bearer, randomPlan()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.problems").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
            mvc.perform(req(HttpMethod.GET, "/api/support/assignments", bearer, null)).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
            mvc.perform(req(HttpMethod.GET, "/api/support/assignments?status=ACTIVE", bearer, null)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Staff are refused everything under /api/support, including HEAD and OPTIONS")
    void staffAreForbidden() throws Exception {
        String staff = token("sup_staff", Role.STAFF, true);
        mvc.perform(req(HttpMethod.GET, "/api/support/options?receivingDepartment=HOUSEKEEPING", staff, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.HEAD, "/api/support/options?receivingDepartment=HOUSEKEEPING", staff, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.OPTIONS, "/api/support/preview", staff, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, "/api/support/preview", staff, randomPlan())).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", staff, randomPlan())).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.GET, "/api/support/assignments", staff, null)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A Staff token that claims the Admin role, and a deactivated Manager, are refused")
    void escalationAndDeactivatedAccountsAreRefused() throws Exception {
        User staff = new User();
        staff.setUsername("sup_esc" + fx.tag.toLowerCase()); staff.setEmail("esc" + fx.tag + "@t.test"); staff.setPassword("x");
        staff.setRole(Role.STAFF); staff.setActive(true); staff.setEmailVerified(true); staff.setCredentialsSetupRequired(false);
        users.saveAndFlush(staff);
        String forged = "Bearer " + jwt.generateToken(staff.getUsername(), Role.ADMIN);
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", forged, randomPlan())).andExpect(status().isForbidden());

        String inactiveManager = token("sup_off", Role.MANAGER, false);
        mvc.perform(req(HttpMethod.GET, "/api/support/assignments", inactiveManager, null)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Only Admin and Manager can end a support; Staff get 403, anonymous 401, and a refused call changes nothing")
    void endSupportAuthorization() throws Exception {
        var s = fx.overloaded(2, 3);
        Object plan = java.util.Map.of("receivingDepartment", s.receiving.getCode(),
                "items", java.util.List.of(java.util.Map.of("staffId", s.donors.get(0).getId().toString(), "taskId", s.tasks.get(0).getId().toString())));
        String created = mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_mk", Role.MANAGER, true), plan))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get(0).get("id").asText();
        String url = "/api/support/assignments/" + id + "/end";

        mvc.perform(req(HttpMethod.POST, url, token("sup_st", Role.STAFF, true), null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, url, null, null)).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.POST, url, "Bearer " + jwt.generateToken("sup_st" + fx.tag.toLowerCase(), Role.ADMIN), null)).andExpect(status().isForbidden());
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(UUID.fromString(id)).orElseThrow().getStatus(), "refused calls ended nothing");

        mvc.perform(req(HttpMethod.POST, url, token("sup_mgr_end", Role.MANAGER, true), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.endReason").value("MANUALLY_ENDED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.homeDepartmentCode").value(s.donor.getCode()));

        // Ending again (a double click, or a stale screen) is a clean 409 with a problem code, for Admin too
        mvc.perform(req(HttpMethod.POST, url, token("sup_adm_end", Role.ADMIN, true), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[0].code").value("ALREADY_ENDED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments/" + UUID.randomUUID() + "/end", token("sup_adm2", Role.ADMIN, true), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.problems[0].code").value("SUPPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no longer exists")));
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments/not-a-uuid/end", token("sup_adm3", Role.ADMIN, true), null))
                .andExpect(status().isBadRequest());
        // Only POST ends support: a GET on the same path is not a way in
        mvc.perform(req(HttpMethod.GET, url, token("sup_adm4", Role.ADMIN, true), null)).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("The reserve override works for Admin and Manager only; Staff are refused even with the acknowledgement set")
    void reserveOverrideOverHttp() throws Exception {
        var s = fx.overloaded(2, 1);
        java.util.Map<String, Object> plain = new java.util.LinkedHashMap<>();
        plain.put("receivingDepartment", s.receiving.getCode());
        plain.put("items", java.util.List.of(java.util.Map.of("staffId", s.donors.get(0).getId().toString(), "taskId", s.tasks.get(0).getId().toString())));
        java.util.Map<String, Object> acked = new java.util.LinkedHashMap<>(plain);
        acked.put("acknowledgeReserveOverride", true);

        String staff = token("sup_ov_staff", Role.STAFF, true);
        mvc.perform(req(HttpMethod.POST, "/api/support/preview", staff, acked)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", staff, acked)).andExpect(status().isForbidden());
        assertEquals(0, supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());

        for (String bearer : new String[]{token("sup_ov_mgr", Role.MANAGER, true), token("sup_ov_adm", Role.ADMIN, true)}) {
            mvc.perform(req(HttpMethod.POST, "/api/support/preview", bearer, plain))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.canOverrideReserve").value(true))
                    .andExpect(jsonPath("$.problems[0].code").value("DONOR_RESERVE"));
            mvc.perform(req(HttpMethod.POST, "/api/support/preview", bearer, acked))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.warnings[0].code").value("RESERVE_OVERRIDE"))
                    .andExpect(jsonPath("$.donors[0].after.freeStaff").value(0));
        }
        // the plain confirmation is still refused; the acknowledged one by a Manager is accepted and reports the override
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_ov_mgr2", Role.MANAGER, true), plain))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value("DONOR_RESERVE"));
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_ov_mgr3", Role.MANAGER, true), acked))
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].reserveOverride").value(true))
                .andExpect(jsonPath("$[0].homeDepartmentCode").value(s.donor.getCode()));
    }

    @Test
    @DisplayName("Only Admin and Manager can remove an ended record from the history; Staff 403, anonymous 401, active records 409")
    void archiveAuthorization() throws Exception {
        var s = fx.overloaded(2, 3);
        Object plan = java.util.Map.of("receivingDepartment", s.receiving.getCode(),
                "items", java.util.List.of(java.util.Map.of("staffId", s.donors.get(0).getId().toString(), "taskId", s.tasks.get(0).getId().toString())));
        String created = mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_ar_mk", Role.MANAGER, true), plan))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get(0).get("id").asText();
        String archive = "/api/support/assignments/" + id + "/archive";

        // active: even a manager is refused, and nothing changes
        mvc.perform(req(HttpMethod.POST, archive, token("sup_ar_m1", Role.MANAGER, true), null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value("SUPPORT_ACTIVE"));
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments/" + id + "/end", token("sup_ar_m2", Role.MANAGER, true), null)).andExpect(status().isOk());

        // unauthorized
        mvc.perform(req(HttpMethod.POST, archive, token("sup_ar_st", Role.STAFF, true), null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, archive, null, null)).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.POST, archive, "Bearer " + jwt.generateToken("sup_ar_st" + fx.tag.toLowerCase(), Role.ADMIN), null)).andExpect(status().isForbidden());
        assertEquals(null, supportRepository.findById(UUID.fromString(id)).orElseThrow().getArchivedAt(), "refused calls archived nothing");

        // authorized
        mvc.perform(req(HttpMethod.POST, archive, token("sup_ar_m3", Role.MANAGER, true), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isNotEmpty()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(req(HttpMethod.POST, archive, token("sup_ar_ad", Role.ADMIN, true), null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.problems[0].code").value("ALREADY_ARCHIVED"));
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments/" + UUID.randomUUID() + "/archive", token("sup_ar_ad2", Role.ADMIN, true), null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.problems[0].code").value("SUPPORT_NOT_FOUND"));
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments/not-a-uuid/archive", token("sup_ar_ad3", Role.ADMIN, true), null)).andExpect(status().isBadRequest());
        mvc.perform(req(HttpMethod.GET, archive, token("sup_ar_ad4", Role.ADMIN, true), null)).andExpect(status().is4xxClientError());

        // the list no longer shows it, but the record exists
        mvc.perform(req(HttpMethod.GET, "/api/support/assignments", token("sup_ar_ad5", Role.ADMIN, true), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
        assertTrue(supportRepository.findById(UUID.fromString(id)).isPresent());
        assertTrue(taskRepository.findById(s.tasks.get(0).getId()).isPresent(), "the task is untouched");
    }

    @Test
    @DisplayName("Unauthenticated requests get 401")
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(req(HttpMethod.GET, "/api/support/options?receivingDepartment=HOUSEKEEPING", null, null)).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.POST, "/api/support/preview", null, randomPlan())).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", null, randomPlan())).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.GET, "/api/support/assignments", null, null)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A malformed request is a 400, not a server error")
    void malformedRequestIsBadRequest() throws Exception {
        String manager = token("sup_bad", Role.MANAGER, true);
        mvc.perform(req(HttpMethod.POST, "/api/support/preview", manager, java.util.Map.of("receivingDepartment", "HOUSEKEEPING", "items", java.util.List.of())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.problems[0].code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("A Manager can confirm a real plan over HTTP; the same call by Staff creates nothing")
    void confirmOverHttp() throws Exception {
        var s = fx.overloaded(2, 3);
        Object plan = java.util.Map.of("receivingDepartment", s.receiving.getCode(),
                "items", java.util.List.of(java.util.Map.of("staffId", s.donors.get(0).getId().toString(), "taskId", s.tasks.get(0).getId().toString())));

        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_staff2", Role.STAFF, true), plan)).andExpect(status().isForbidden());
        assertEquals(0, supportRepository.findAll().stream().filter(x -> x.getReceivingDepartmentCode().equals(s.receiving.getCode())).count());

        mvc.perform(req(HttpMethod.POST, "/api/support/assignments", token("sup_mgr2", Role.MANAGER, true), plan))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].homeDepartmentCode").value(s.donor.getCode()))
                .andExpect(jsonPath("$[0].receivingDepartmentCode").value(s.receiving.getCode()))
                .andExpect(jsonPath("$[0].approvedBy").value("sup_mgr2" + fx.tag.toLowerCase()));
    }
}
