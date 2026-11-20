package com.hotel.workload.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrant;
import com.hotel.workload.staff.ManagerViewGrantChange;
import com.hotel.workload.staff.ManagerViewGrantChangeRepository;
import com.hotel.workload.staff.ManagerViewGrantRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Administrator-only system role management, exercised over HTTP with real JWTs (the whole security filter chain, URL rules and services)
 * against real, rolled-back records: every supported transition, the password confirmation for ADMIN changes, Managers and Staff refused,
 * self-change and last-administrator protection, Manager-record validation, grant removal, session invalidation and the audit history.
 */
@SpringBootTest
@Transactional
public class RoleManagementTest {

    private static final String PASSWORD = "password"; // the password ScopeFixtures gives every account

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private ManagerViewGrantChangeRepository grantChangeRepository;
    @Autowired private UserRoleChangeRepository roleChanges;
    @Autowired private RoleChangeService roleChangeService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private ScopeFixtures fixtures;
    private Department home;
    private Department granted;

    private User admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department("RM_HOME");
        granted = fixtures.department("RM_GRANT");
        admin = fixtures.account("rm_admin", Role.ADMIN, true);
        adminToken = bearer(admin);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String bearer(User user) {
        return "Bearer " + jwt.generateToken(user.getUsername(), user.getRole());
    }

    /** A token exactly as sign-in issues it: carrying the account's password version and session version as they are in the database now. */
    private String session(User user) {
        User current = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwt.generateToken(current.getUsername(), current.getRole(), current.getPasswordChangedAt(), current.getSessionsValidAfter());
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private MvcResult changeRole(User target, Role role, String password, String token) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("role", role.name());
        if (password != null) body.put("currentPassword", password);
        return call(HttpMethod.PUT, "/api/users/" + target.getId() + "/role", token, body);
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private Role roleOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getRole();
    }

    private void grant(User manager, Department department) {
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(manager);
        grant.setDepartment(department);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername(admin.getUsername());
        grantRepository.saveAndFlush(grant);
    }

    /** Every other active administrator is switched off (inside the rolled-back transaction), so "last active administrator" is real. */
    private void makeOnlyActiveAdmin(User only) {
        for (User other : userRepository.findAll()) {
            if (other.getRole() == Role.ADMIN && other.isActive() && !other.getId().equals(only.getId())) {
                other.setActive(false);
                userRepository.saveAndFlush(other);
            }
        }
    }

    // ── supported transitions ──────────────────────────────────────────────────

    @Test
    @DisplayName("STAFF -> MANAGER: no password needed; the Staff record and its department stay; no grant is created; the change is audited")
    void staffToManager() throws Exception {
        Staff staff = fixtures.staff("rm_s2m", home);
        MvcResult result = changeRole(staff.getUser(), Role.MANAGER, null, adminToken);
        assertEquals(200, status(result), text(result));
        assertEquals("MANAGER", json.readTree(text(result)).get("role").asText());
        assertEquals(Role.MANAGER, roleOf(staff.getUser()));
        Staff after = staffRepository.findByUserUsername("rm_s2m").orElseThrow();
        assertEquals(home.getCode(), after.getDepartment().getCode());
        assertTrue(after.isActive());
        assertTrue(grantRepository.findByManagerId(staff.getUser().getId()).isEmpty(), "a promotion grants no additional department");
        UserRoleChange record = roleChanges.findByTargetUserIdOrderByChangedAtDesc(staff.getUser().getId()).get(0);
        assertEquals(Role.STAFF, record.getPreviousRole());
        assertEquals(Role.MANAGER, record.getNewRole());
        assertEquals("rm_admin", record.getChangedByUsername());
        assertNotNull(record.getChangedAt());
    }

    @Test
    @DisplayName("STAFF -> ADMIN needs the acting administrator's password; the Staff record is kept")
    void staffToAdmin() throws Exception {
        Staff staff = fixtures.staff("rm_s2a", home);
        assertEquals(400, status(changeRole(staff.getUser(), Role.ADMIN, null, adminToken)));
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
        MvcResult result = changeRole(staff.getUser(), Role.ADMIN, PASSWORD, adminToken);
        assertEquals(200, status(result), text(result));
        assertEquals(Role.ADMIN, roleOf(staff.getUser()));
        assertTrue(staffRepository.findByUserUsername("rm_s2a").isPresent(), "the Staff record is never deleted by a role change");
    }

    @Test
    @DisplayName("MANAGER -> STAFF: every additional-department grant ends (audited, recorded), the Staff record and primary department stay")
    void managerToStaff() throws Exception {
        Staff manager = fixtures.manager("rm_m2s", home);
        grant(manager.getUser(), granted);
        grant(manager.getUser(), fixtures.department("RM_GRANT2"));
        MvcResult result = changeRole(manager.getUser(), Role.STAFF, null, adminToken);
        assertEquals(200, status(result), text(result));
        assertEquals(Role.STAFF, roleOf(manager.getUser()));
        assertTrue(grantRepository.findByManagerId(manager.getUser().getId()).isEmpty());
        List<ManagerViewGrantChange> revoked = grantChangeRepository.findByManagerUserIdOrderByChangedAtDesc(manager.getUser().getId());
        assertEquals(2, revoked.size());
        assertTrue(revoked.stream().allMatch(c -> ManagerViewGrantChange.ACTION_REVOKED.equals(c.getAction())
                && ManagerViewGrantChange.SOURCE_ROLE_CHANGE.equals(c.getChangeSource()) && "rm_admin".equals(c.getChangedByUsername())));
        assertEquals(2, roleChanges.findByTargetUserIdOrderByChangedAtDesc(manager.getUser().getId()).get(0).getRevokedGrants());
        assertEquals(home.getCode(), staffRepository.findByUserUsername("rm_m2s").orElseThrow().getDepartment().getCode());
    }

    @Test
    @DisplayName("MANAGER -> ADMIN needs the password; the Manager's grants end too, so a later demotion cannot bring them back")
    void managerToAdmin() throws Exception {
        Staff manager = fixtures.manager("rm_m2a", home);
        grant(manager.getUser(), granted);
        assertEquals(400, status(changeRole(manager.getUser(), Role.ADMIN, "wrong", adminToken)));
        assertEquals(Role.MANAGER, roleOf(manager.getUser()));
        assertEquals(1, grantRepository.findByManagerId(manager.getUser().getId()).size(), "a refused change changes nothing");
        assertEquals(200, status(changeRole(manager.getUser(), Role.ADMIN, PASSWORD, adminToken)));
        assertEquals(Role.ADMIN, roleOf(manager.getUser()));
        assertTrue(grantRepository.findByManagerId(manager.getUser().getId()).isEmpty());
    }

    @Test
    @DisplayName("ADMIN -> MANAGER needs the password and a valid Manager record; they keep their department")
    void adminToManager() throws Exception {
        Staff other = fixtures.staffRecord("rm_a2m", Role.ADMIN, home);
        assertEquals(400, status(changeRole(other.getUser(), Role.MANAGER, null, adminToken)));
        MvcResult result = changeRole(other.getUser(), Role.MANAGER, PASSWORD, adminToken);
        assertEquals(200, status(result), text(result));
        assertEquals(Role.MANAGER, roleOf(other.getUser()));
        assertEquals(home.getCode(), staffRepository.findByUserUsername("rm_a2m").orElseThrow().getDepartment().getCode());
        assertTrue(grantRepository.findByManagerId(other.getUser().getId()).isEmpty());
    }

    @Test
    @DisplayName("ADMIN -> STAFF needs the password; the Staff record stays")
    void adminToStaff() throws Exception {
        Staff other = fixtures.staffRecord("rm_a2s", Role.ADMIN, home);
        assertEquals(400, status(changeRole(other.getUser(), Role.STAFF, null, adminToken)));
        assertEquals(200, status(changeRole(other.getUser(), Role.STAFF, PASSWORD, adminToken)));
        assertEquals(Role.STAFF, roleOf(other.getUser()));
        assertTrue(staffRepository.findByUserUsername("rm_a2s").isPresent());
    }

    @Test
    @DisplayName("Changing to the role the account already has, a missing role and an unknown account are refused; nothing is recorded")
    void unchangedMissingAndUnknown() throws Exception {
        Staff staff = fixtures.staff("rm_same", home);
        assertEquals(400, status(changeRole(staff.getUser(), Role.STAFF, null, adminToken)));
        assertEquals(400, status(call(HttpMethod.PUT, "/api/users/" + staff.getUser().getId() + "/role", adminToken, Map.of())));
        assertEquals(404, status(call(HttpMethod.PUT, "/api/users/" + java.util.UUID.randomUUID() + "/role", adminToken, Map.of("role", "MANAGER"))));
        assertTrue(roleChanges.findByTargetUserIdOrderByChangedAtDesc(staff.getUser().getId()).isEmpty());
    }

    // ── who may change roles ─────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager can never change a role, even with a valid body: 403 and nothing changes (also for their own account and for Staff)")
    void managerCannotChangeRoles() throws Exception {
        Staff manager = fixtures.manager("rm_actor_mgr", home);
        Staff staff = fixtures.staff("rm_victim", home);
        String token = bearer(manager.getUser());
        for (Role role : Role.values()) {
            assertEquals(403, status(changeRole(staff.getUser(), role, PASSWORD, token)), "promote/demote Staff to " + role);
            assertEquals(403, status(changeRole(manager.getUser(), role, PASSWORD, token)), "self change to " + role);
        }
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
        assertEquals(Role.MANAGER, roleOf(manager.getUser()));
        assertEquals(403, status(call(HttpMethod.GET, "/api/users/" + staff.getUser().getId() + "/role-history", token, null)), "no history for Managers");
        assertTrue(roleChanges.findAll().stream().noneMatch(c -> "rm_victim".equals(c.getTargetUsername())));
    }

    @Test
    @DisplayName("Staff and signed-out callers cannot change roles or read the history")
    void staffAndAnonymousRefused() throws Exception {
        Staff staff = fixtures.staff("rm_actor_staff", home);
        String token = bearer(staff.getUser());
        assertEquals(403, status(changeRole(staff.getUser(), Role.ADMIN, PASSWORD, token)));
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
        assertEquals(403, status(call(HttpMethod.GET, "/api/users/" + staff.getUser().getId() + "/role-history", token, null)));
        int anonymous = status(changeRole(staff.getUser(), Role.ADMIN, PASSWORD, null));
        assertTrue(anonymous == 401 || anonymous == 403, "signed out: " + anonymous);
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
    }

    @Test
    @DisplayName("The service itself refuses a non-administrator (a Manager's authentication reaching it directly)")
    void serviceRefusesNonAdminDirectly() {
        Staff manager = fixtures.manager("rm_direct_mgr", home);
        Staff staff = fixtures.staff("rm_direct_staff", home);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("rm_direct_mgr", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> roleChangeService.changeRole(staff.getUser().getId(), Role.MANAGER, null, "127.0.0.1"));
        assertEquals(403, refused.getStatusCode().value());
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
        assertEquals(Role.MANAGER, roleOf(manager.getUser()));
    }

    // ── safeguards ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Nobody changes their own role, administrators included (no accidental lock-out)")
    void ownRoleRefused() throws Exception {
        MvcResult result = changeRole(admin, Role.STAFF, PASSWORD, adminToken);
        assertEquals(400, status(result), text(result));
        assertEquals(Role.ADMIN, roleOf(admin));
        assertTrue(roleChanges.findByTargetUserIdOrderByChangedAtDesc(admin.getId()).isEmpty());
    }

    @Test
    @DisplayName("An administrator who is no longer active cannot change roles, so the last active administrator can never be demoted by a stale authentication")
    void inactiveActorRefusedAndLastAdminKept() {
        User lastAdmin = fixtures.account("rm_last_admin", Role.ADMIN, true);
        fixtures.account("rm_ghost_admin", Role.ADMIN, false); // an inactive administrator acting through a still-valid authentication
        makeOnlyActiveAdmin(lastAdmin);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("rm_ghost_admin", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        for (Role role : new Role[] {Role.STAFF, Role.MANAGER}) {
            ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                    () -> roleChangeService.changeRole(lastAdmin.getId(), role, PASSWORD, "127.0.0.1"));
            assertEquals(403, refused.getStatusCode().value(), role.name());
        }
        assertEquals(Role.ADMIN, roleOf(lastAdmin));
        assertTrue(userRepository.findById(lastAdmin.getId()).orElseThrow().isActive());
        assertTrue(roleChanges.findByTargetUserIdOrderByChangedAtDesc(lastAdmin.getId()).isEmpty());
    }

    @Test
    @DisplayName("With two active administrators one may be demoted by the other")
    void oneOfTwoAdminsCanBeDemoted() throws Exception {
        makeOnlyActiveAdmin(admin);
        User second = fixtures.account("rm_second_admin", Role.ADMIN, true);
        fixtures.staffRecord("rm_second_admin", Role.ADMIN, home);
        assertEquals(200, status(changeRole(second, Role.STAFF, PASSWORD, adminToken)));
        assertEquals(Role.STAFF, roleOf(second));
        assertEquals(Role.ADMIN, roleOf(admin));
    }

    @Test
    @DisplayName("The password check is rate limited and a wrong password is a 400 (never 401: the browser would sign the administrator out)")
    void passwordIsRateLimited() throws Exception {
        // its own acting administrator: the limiter is shared by the whole test run and keyed by client and account
        String limitedAdmin = bearer(fixtures.account("rm_limit_admin", Role.ADMIN, true));
        Staff staff = fixtures.staff("rm_limit", home);
        for (int i = 0; i < 5; i++) {
            assertEquals(400, status(changeRole(staff.getUser(), Role.ADMIN, "wrong-" + i, limitedAdmin)), "attempt " + i);
        }
        assertEquals(429, status(changeRole(staff.getUser(), Role.ADMIN, PASSWORD, limitedAdmin)), "even the right password waits once the limit is hit");
        assertEquals(Role.STAFF, roleOf(staff.getUser()), "nothing changed while the limit held");
        assertEquals(200, status(changeRole(staff.getUser(), Role.ADMIN, PASSWORD, adminToken)), "other administrators are not affected");
        assertEquals(Role.ADMIN, roleOf(staff.getUser()));
    }

    // ── Manager record validation ───────────────────────────────────────────────

    @Test
    @DisplayName("Becoming a Manager needs a linked Staff record with an active department: 409 and nothing changes otherwise")
    void managerRecordIsValidated() throws Exception {
        User noRecord = fixtures.account("rm_no_record", Role.STAFF, true);
        MvcResult a = changeRole(noRecord, Role.MANAGER, null, adminToken);
        assertEquals(409, status(a), text(a));
        assertTrue(text(a).contains("no linked staff record"));

        Staff noDepartment = fixtures.staff("rm_no_dept", home);
        noDepartment.setDepartment(null);
        staffRepository.saveAndFlush(noDepartment);
        MvcResult b = changeRole(noDepartment.getUser(), Role.MANAGER, null, adminToken);
        assertEquals(409, status(b), text(b));
        assertTrue(text(b).contains("no department"));

        Department shut = fixtures.department("RM_SHUT");
        Staff inShut = fixtures.staff("rm_in_shut", shut);
        shut.setActive(false);
        departmentRepository.saveAndFlush(shut);
        MvcResult c = changeRole(inShut.getUser(), Role.MANAGER, null, adminToken);
        assertEquals(409, status(c), text(c));
        assertTrue(text(c).contains("inactive"));

        assertEquals(Role.STAFF, roleOf(noRecord));
        assertEquals(Role.STAFF, roleOf(noDepartment.getUser()));
        assertEquals(Role.STAFF, roleOf(inShut.getUser()));
        assertTrue(roleChanges.findAll().stream().noneMatch(r -> r.getTargetUsername().startsWith("rm_no_") || "rm_in_shut".equals(r.getTargetUsername())));
    }

    @Test
    @DisplayName("An Administrator account with no Staff record cannot become a Manager (the department comes from the Staff record)")
    void adminWithoutStaffRecordCannotBecomeManager() throws Exception {
        User bare = fixtures.account("rm_bare_admin", Role.ADMIN, true);
        assertEquals(409, status(changeRole(bare, Role.MANAGER, PASSWORD, adminToken)));
        assertEquals(Role.ADMIN, roleOf(bare));
        // but it can become Staff (no Staff record is invented either)
        assertEquals(200, status(changeRole(bare, Role.STAFF, PASSWORD, adminToken)));
        assertTrue(staffRepository.findByUserUsername("rm_bare_admin").isEmpty());
    }

    // ── sessions ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every token issued before a role change is refused, in the very same second and millisecond too; a token issued afterwards works. No sleeping: the timing is deterministic")
    void everyEarlierTokenEndsAtOnce() throws Exception {
        Staff staff = fixtures.staff("rm_session", home);
        String before = session(staff.getUser());
        String beforeWithoutClaim = bearer(staff.getUser()); // the older token form (no session version): fine while the account was never invalidated
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", before, null)));
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", beforeWithoutClaim, null)));

        assertEquals(200, status(changeRole(staff.getUser(), Role.MANAGER, null, adminToken)));
        assertEquals(401, status(call(HttpMethod.GET, "/api/profile", before, null)), "a token issued just before the change");
        assertEquals(401, status(call(HttpMethod.GET, "/api/profile", beforeWithoutClaim, null)), "so is one without the claim");
        String afterFirst = session(staff.getUser());
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", afterFirst, null)), "signing in again works at once");

        // straight back again, with no pause: the token issued between the two changes ends too
        assertEquals(200, status(changeRole(staff.getUser(), Role.STAFF, null, adminToken)));
        assertEquals(401, status(call(HttpMethod.GET, "/api/profile", afterFirst, null)));
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", session(staff.getUser()), null)));
        assertEquals(Role.STAFF, roleOf(staff.getUser()));
    }

    @Test
    @DisplayName("A role change also ends the sessions of an Administrator being demoted and of a Manager being promoted: the old token never keeps access")
    void oldTokensNeverRetainAccessAfterPromotionOrDemotion() throws Exception {
        Staff manager = fixtures.manager("rm_old_mgr", home);
        String managerBefore = session(manager.getUser());
        assertEquals(200, status(call(HttpMethod.GET, "/api/staff", managerBefore, null)));
        assertEquals(200, status(changeRole(manager.getUser(), Role.ADMIN, PASSWORD, adminToken)));
        assertEquals(401, status(call(HttpMethod.GET, "/api/staff", managerBefore, null)), "promotion ends the old session");

        Staff other = fixtures.staffRecord("rm_old_admin", Role.ADMIN, home);
        String adminBefore = session(other.getUser());
        assertEquals(200, status(call(HttpMethod.GET, "/api/users", adminBefore, null)));
        assertEquals(200, status(changeRole(other.getUser(), Role.STAFF, PASSWORD, adminToken)));
        assertEquals(401, status(call(HttpMethod.GET, "/api/users", adminBefore, null)), "demotion ends the old session: no Admin access is kept");
        // a token that still CLAIMS the Admin role, even with the current session version, gets the database role (STAFF)
        String claimsAdmin = "Bearer " + jwt.generateToken("rm_old_admin", Role.ADMIN, userRepository.findById(other.getUser().getId()).orElseThrow().getPasswordChangedAt(),
                userRepository.findById(other.getUser().getId()).orElseThrow().getSessionsValidAfter());
        assertEquals(403, status(call(HttpMethod.GET, "/api/users", claimsAdmin, null)));
    }

    @Test
    @DisplayName("A refused change (wrong password, own account, invalid Manager record) leaves the account's sessions alone")
    void refusedChangesKeepSessions() throws Exception {
        Staff staff = fixtures.staff("rm_keep", home);
        String token = session(staff.getUser());
        assertEquals(400, status(changeRole(staff.getUser(), Role.ADMIN, "wrong", adminToken)));
        assertEquals(400, status(changeRole(staff.getUser(), Role.STAFF, null, adminToken)));
        assertNull(userRepository.findById(staff.getUser().getId()).orElseThrow().getSessionsValidAfter());
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", token, null)));
    }

    @Test
    @DisplayName("The password version still ends sessions: a token with the current session version but an old password version is refused")
    void passwordVersionStillEnforced() throws Exception {
        Staff staff = fixtures.staff("rm_pwv", home);
        assertEquals(200, status(changeRole(staff.getUser(), Role.MANAGER, null, adminToken)));
        String token = session(staff.getUser());
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", token, null)));
        userRepository.updatePassword(staff.getUser().getId(), passwordEncoder.encode("Another@456"), LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertEquals(401, status(call(HttpMethod.GET, "/api/profile", token, null)), "the password change ended it");
        assertEquals(200, status(call(HttpMethod.GET, "/api/profile", session(staff.getUser()), null)));
    }

    @Test
    @DisplayName("A demoted Manager loses Manager rights immediately, whatever the token says")
    void demotedManagerLosesRightsAtOnce() throws Exception {
        Staff manager = fixtures.manager("rm_demoted", home);
        assertEquals(200, status(call(HttpMethod.GET, "/api/staff", session(manager.getUser()), null)));
        assertEquals(200, status(changeRole(manager.getUser(), Role.STAFF, null, adminToken)));
        User now = userRepository.findById(manager.getUser().getId()).orElseThrow();
        String claimsManager = "Bearer " + jwt.generateToken("rm_demoted", Role.MANAGER, now.getPasswordChangedAt(), now.getSessionsValidAfter());
        assertEquals(403, status(call(HttpMethod.GET, "/api/staff", claimsManager, null)), "the database role (STAFF) decides");
    }

    @Test
    @DisplayName("Session versions only ever go up, and a token matches only the account's current one")
    void sessionVersionRules() {
        LocalDateTime future = LocalDateTime.now().plusMinutes(5).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(future.plusNanos(1_000_000), RoleChangeService.nextSessionVersion(future), "equal or earlier than the stored one: previous + 1 ms");
        assertTrue(RoleChangeService.nextSessionVersion(null).isAfter(LocalDateTime.now().minusMinutes(1)));
        LocalDateTime valid = LocalDateTime.of(2026, 1, 1, 12, 0, 30, 5_000_000);
        long version = JwtUtil.sessionVersion(valid);
        assertTrue(JwtUtil.matchesCurrentSession(version, valid));
        assertFalse(JwtUtil.matchesCurrentSession(version - 1, valid), "an older version");
        assertFalse(JwtUtil.matchesCurrentSession(null, valid), "no claim once the account was invalidated");
        assertFalse(JwtUtil.matchesCurrentSession(0L, valid));
        assertTrue(JwtUtil.matchesCurrentSession(null, null), "never invalidated: no claim needed");
        assertTrue(JwtUtil.matchesCurrentSession(0L, null));
        assertFalse(JwtUtil.matchesCurrentSession(version, null), "a claim that no longer exists on the account");
    }

    // ── audit history ───────────────────────────────────────────────────────

    @Test
    @DisplayName("The history lists every change newest first for Administrators: actor, target, previous and new role, time; no secrets")
    void historyIsRecordedAndAdminOnly() throws Exception {
        Staff staff = fixtures.staff("rm_hist", home);
        assertEquals(200, status(changeRole(staff.getUser(), Role.MANAGER, null, adminToken)));
        Thread.sleep(20); // the history is ordered by time: two changes in the same instant have no defined order
        assertEquals(200, status(changeRole(staff.getUser(), Role.STAFF, null, adminToken)));
        MvcResult result = call(HttpMethod.GET, "/api/users/" + staff.getUser().getId() + "/role-history", adminToken, null);
        assertEquals(200, status(result), text(result));
        JsonNode rows = json.readTree(text(result));
        assertEquals(2, rows.size());
        assertEquals("MANAGER", rows.get(0).get("previousRole").asText(), "newest first");
        assertEquals("STAFF", rows.get(0).get("newRole").asText());
        assertEquals("rm_admin", rows.get(0).get("changedBy").asText());
        assertEquals("rm_hist", rows.get(0).get("targetUsername").asText());
        assertNotNull(rows.get(0).get("changedAt"));
        String body = text(result).toLowerCase();
        assertFalse(body.contains("password") || body.contains("token") || body.contains("email"), "nothing sensitive in the history");
        assertNull(rows.get(0).get("currentPassword"));
    }
}
