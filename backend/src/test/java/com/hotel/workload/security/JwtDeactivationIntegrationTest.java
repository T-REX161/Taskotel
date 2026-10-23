package com.hotel.workload.security;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
public class JwtDeactivationIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private StaffService staffService;

    @Autowired
    private JwtUtil jwtUtil;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private User createTestUser(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.com");
        user.setPassword("password123");
        user.setRole(role);
        user.setActive(active);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.save(user);
    }

    private Staff createTestStaff(User user, boolean active) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName(user.getUsername() + " FullName");
        staff.setEmail(user.getEmail());
        staff.setDepartment(TestDepartments.FRONT_DESK);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(active);
        return staffRepository.save(staff);
    }

    @Test
    @DisplayName("Valid JWT from an active account can access a protected endpoint")
    public void testActiveAccountJwtCanAccessProtectedEndpoint() throws Exception {
        User user = createTestUser("active_jwt_user", Role.STAFF, true);
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A previously valid JWT is rejected after the account is deactivated")
    public void testPreviouslyValidJwtRejectedAfterAccountDeactivated() throws Exception {
        User user = createTestUser("deact_jwt_user", Role.STAFF, true);
        Staff staff = createTestStaff(user, true);

        // Issue JWT while user is active
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // Verify token works initially
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Deactivate staff member (which also sets user.active = false)
        staffService.deactivateStaff(staff.getId());

        // Verify the exact same token is now rejected with 401 Unauthorized
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A reactivated staff member can access protected endpoints again")
    public void testReactivatedStaffCanAccessProtectedEndpoints() throws Exception {
        User user = createTestUser("react_jwt_user", Role.STAFF, true);
        Staff staff = createTestStaff(user, true);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // Deactivate
        staffService.deactivateStaff(staff.getId());
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // Reactivate
        staffService.activateStaff(staff.getId());

        // Token works again
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A deleted account's JWT is rejected with 401 Unauthorized")
    public void testDeletedAccountJwtIsRejected() throws Exception {
        User user = createTestUser("deleted_jwt_user", Role.STAFF, true);
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // Token works before deletion
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Delete user
        userRepository.delete(user);
        userRepository.flush();

        // Same token is now rejected
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Deactivating one staff member does not invalidate other users' tokens")
    public void testDeactivatingOneStaffDoesNotInvalidateOtherUsers() throws Exception {
        User userA = createTestUser("user_a", Role.STAFF, true);
        Staff staffA = createTestStaff(userA, true);
        String tokenA = jwtUtil.generateToken(userA.getUsername(), userA.getRole());

        User userB = createTestUser("user_b", Role.STAFF, true);
        createTestStaff(userB, true);
        String tokenB = jwtUtil.generateToken(userB.getUsername(), userB.getRole());

        // Both work initially
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        // Deactivate user A only
        staffService.deactivateStaff(staffA.getId());

        // User A's token is rejected
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnauthorized());

        // User B's token remains completely valid
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Expired and invalid-signature JWTs remain rejected with 401 Unauthorized")
    public void testExpiredAndInvalidSignatureJwtsRemainRejected() throws Exception {
        // Expired JWT using negative expiration
        JwtUtil expiredJwtUtil = new JwtUtil("dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=", -1000L);
        String expiredToken = expiredJwtUtil.generateToken("some_user", Role.STAFF);

        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        // Invalid signature / different secret JWT
        JwtUtil untrustedJwtUtil = new JwtUtil("YW5vdGhlci12YWxpZC1qd3Qtc2VjcmV0LWtleS1mb3ItdGVzdHMtMzJieXRlcw==", 86400000L);
        String untrustedToken = untrustedJwtUtil.generateToken("some_user", Role.STAFF);

        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer " + untrustedToken))
                .andExpect(status().isUnauthorized());

        // Malformed token
        mockMvc.perform(get("/api/test/staff")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Role-based authorization is preserved when using JWT")
    public void testRoleBasedAuthorizationPreserved() throws Exception {
        User staffUser = createTestUser("role_staff_user", Role.STAFF, true);
        String staffToken = jwtUtil.generateToken(staffUser.getUsername(), staffUser.getRole());

        User adminUser = createTestUser("role_admin_user", Role.ADMIN, true);
        String adminToken = jwtUtil.generateToken(adminUser.getUsername(), adminUser.getRole());

        // Staff can access /api/test/staff but is forbidden on /api/test/admin
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        // Admin can access both /api/test/staff and /api/test/admin
        mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
