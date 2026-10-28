package com.hotel.workload.workload;

import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The recommendations endpoint with real JWTs for real (rolled-back) database users, so the whole chain runs:
 * JwtAuthenticationFilter (signature, account lookup, role taken from the database) and SecurityConfig.
 * WorkloadRecommendationControllerTest covers the same rules with mock users.
 */
@SpringBootTest
@Transactional
public class WorkloadRecommendationJwtAccessTest {

    private static final String URL = "/api/workload/recommendations";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User user(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.com");
        user.setPassword("not-used");
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getUsername(), user.getRole());
    }

    @Test
    @DisplayName("An Admin JWT is allowed")
    public void adminJwtIsAllowed() throws Exception {
        User admin = user("rec_jwt_admin", Role.ADMIN, true);
        mockMvc.perform(get(URL).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations").isArray());
    }

    @Test
    @DisplayName("A Manager JWT is allowed")
    public void managerJwtIsAllowed() throws Exception {
        User manager = user("rec_jwt_manager", Role.MANAGER, true);
        mockMvc.perform(get(URL).header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations").isArray());
    }

    @Test
    @DisplayName("A Staff JWT is forbidden")
    public void staffJwtIsForbidden() throws Exception {
        User staff = user("rec_jwt_staff", Role.STAFF, true);
        mockMvc.perform(get(URL).header("Authorization", bearer(staff))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A Staff account cannot escalate by presenting a token that claims the Admin role")
    public void roleClaimInTokenDoesNotOverrideTheDatabaseRole() throws Exception {
        User staff = user("rec_jwt_escalate", Role.STAFF, true);
        String forged = "Bearer " + jwtUtil.generateToken(staff.getUsername(), Role.ADMIN);
        mockMvc.perform(get(URL).header("Authorization", forged)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A deactivated Manager's token is refused")
    public void deactivatedManagerIsRefused() throws Exception {
        User manager = user("rec_jwt_inactive", Role.MANAGER, false);
        mockMvc.perform(get(URL).header("Authorization", bearer(manager))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("No token, a malformed token or a tampered token get 401")
    public void missingOrInvalidTokensGet401() throws Exception {
        User admin = user("rec_jwt_tamper", Role.ADMIN, true);
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header("Authorization", "Bearer not.a.token")).andExpect(status().isUnauthorized());
        // Change the first character of the signature (appending a character would not change what the signature decodes to)
        String valid = bearer(admin);
        int signatureStart = valid.lastIndexOf('.') + 1;
        String tampered = valid.substring(0, signatureStart) + (valid.charAt(signatureStart) == 'A' ? 'B' : 'A') + valid.substring(signatureStart + 1);
        mockMvc.perform(get(URL).header("Authorization", tampered)).andExpect(status().isUnauthorized());
    }

    // ── the rule covers every method, not only GET ───────────────────────────────────────────────────────────
    // HEAD is answered by the GET handler, so a GET-only rule would let any signed-in user run the calculation.
    // OPTIONS policy: it follows the same rule. The app has no CORS configuration (the browser reaches it through
    // the same-origin dev proxy), so there is no cross-origin preflight to protect or to break.

    @Test
    @DisplayName("Admin: GET and HEAD are authorized")
    public void adminGetAndHeadAreAuthorized() throws Exception {
        String admin = bearer(user("rec_m_admin", Role.ADMIN, true));
        mockMvc.perform(request(HttpMethod.GET, URL).header("Authorization", admin)).andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.HEAD, URL).header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Manager: GET and HEAD are authorized")
    public void managerGetAndHeadAreAuthorized() throws Exception {
        String manager = bearer(user("rec_m_manager", Role.MANAGER, true));
        mockMvc.perform(request(HttpMethod.GET, URL).header("Authorization", manager)).andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.HEAD, URL).header("Authorization", manager)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Staff: GET and HEAD return 403")
    public void staffGetAndHeadAreForbidden() throws Exception {
        String staff = bearer(user("rec_m_staff", Role.STAFF, true));
        mockMvc.perform(request(HttpMethod.GET, URL).header("Authorization", staff)).andExpect(status().isForbidden());
        mockMvc.perform(request(HttpMethod.HEAD, URL).header("Authorization", staff)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated GET, HEAD and OPTIONS return 401")
    public void anonymousRequestsGet401() throws Exception {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS}) {
            mockMvc.perform(request(method, URL)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("OPTIONS follows the same rule: allowed for Admin and Manager, 403 for Staff")
    public void optionsFollowsTheSameRule() throws Exception {
        String admin = bearer(user("rec_o_admin", Role.ADMIN, true));
        String manager = bearer(user("rec_o_manager", Role.MANAGER, true));
        String staff = bearer(user("rec_o_staff", Role.STAFF, true));
        mockMvc.perform(request(HttpMethod.OPTIONS, URL).header("Authorization", admin)).andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.OPTIONS, URL).header("Authorization", manager)).andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.OPTIONS, URL).header("Authorization", staff)).andExpect(status().isForbidden());
    }
}
