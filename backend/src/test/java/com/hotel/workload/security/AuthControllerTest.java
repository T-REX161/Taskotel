package com.hotel.workload.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.verification.VerificationCodeService;
import com.hotel.workload.verification.VerifyCodeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import com.hotel.workload.verification.VerificationResponse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class AuthControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.hotel.workload.verification.EmailService emailService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        org.mockito.Mockito.when(emailService.isConfigured()).thenReturn(true);
    }

    @Test
    @Transactional
    @DisplayName("Invalid username returns 401 Unauthorized and does not send verification email")
    public void testLogin_InvalidUsername_ReturnsUnauthorized() throws Exception {
        LoginRequest request = new LoginRequest("nonexistent_user", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    @DisplayName("Invalid password returns 401 Unauthorized and does not send verification email")
    public void testLogin_InvalidPassword_ReturnsUnauthorized() throws Exception {
        User user = new User();
        user.setUsername("testuser_invalidpass");
        user.setEmail("invalidpass@hotel.com");
        user.setPassword(passwordEncoder.encode("correctpassword"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(false);
        userRepository.save(user);

        LoginRequest request = new LoginRequest("testuser_invalidpass", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // Verify that no code hash was generated on the user
        User reloaded = userRepository.findByUsername("testuser_invalidpass").orElseThrow();
        assertNull(reloaded.getVerificationCodeHash(), "No verification code should be generated for invalid login");
    }

    @Test
    @Transactional
    @DisplayName("Valid Admin login returns JWT, role ADMIN, and does not require email verification")
    public void testLogin_ValidAdmin_ReturnsJwt() throws Exception {
        User admin = new User();
        admin.setUsername("authctrl_admin_login");
        admin.setEmail("authctrl_admin@hotel.com");
        admin.setPassword(passwordEncoder.encode("adminpass123"));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        userRepository.save(admin);

        LoginRequest request = new LoginRequest("authctrl_admin_login", "adminpass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("authctrl_admin_login"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    @Transactional
    @DisplayName("Valid Manager login returns JWT, role MANAGER, and does not require email verification")
    public void testLogin_ValidManager_ReturnsJwt() throws Exception {
        User manager = new User();
        manager.setUsername("authctrl_manager_login");
        manager.setEmail("authctrl_manager@hotel.com");
        manager.setPassword(passwordEncoder.encode("managerpass123"));
        manager.setRole(Role.MANAGER);
        manager.setActive(true);
        userRepository.save(manager);

        LoginRequest request = new LoginRequest("authctrl_manager_login", "managerpass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("authctrl_manager_login"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    @Transactional
    @DisplayName("Valid verified Staff login returns JWT, role STAFF, and does not require email verification")
    public void testLogin_VerifiedStaff_ReturnsJwt() throws Exception {
        User staff = new User();
        staff.setUsername("authctrl_staff_verified_login");
        staff.setEmail("authctrl_staff_verified@hotel.com");
        staff.setPassword(passwordEncoder.encode("staffpass123"));
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        staff.setEmailVerified(true);
        staff.setCredentialsSetupRequired(false);
        userRepository.save(staff);

        LoginRequest request = new LoginRequest("authctrl_staff_verified_login", "staffpass123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("authctrl_staff_verified_login"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    @Transactional
    @DisplayName("Valid unverified Staff login does NOT return JWT, triggers code generation, and indicates verification required")
    public void testLogin_UnverifiedStaff_TriggersVerificationAndDoesNotReturnJwt() throws Exception {
        User staff = new User();
        staff.setUsername("teststaff_unverified_login");
        staff.setEmail("staff_unverified@hotel.com");
        staff.setPassword(passwordEncoder.encode("tempPass123"));
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        staff.setEmailVerified(false);
        userRepository.save(staff);

        LoginRequest request = new LoginRequest("teststaff_unverified_login", "tempPass123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                // Ensure JWT is NEVER returned
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.username").value("teststaff_unverified_login"))
                .andExpect(jsonPath("$.email").value("staff_unverified@hotel.com"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.message", containsString("Email verification required")))
                // Ensure sensitive fields are NEVER returned in response
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.verificationCode").doesNotExist())
                .andExpect(jsonPath("$.verificationCodeHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.toLowerCase().contains("hash"), "Response must not contain hash information");

        // Verify that database record was updated with newly generated verification code hash and expiry
        User updatedStaff = userRepository.findByUsername("teststaff_unverified_login").orElseThrow();
        assertNotNull(updatedStaff.getVerificationCodeHash(), "A verification code hash must be saved in database");
        assertNotNull(updatedStaff.getVerificationCodeExpiresAt(), "Expiration must be set");
        assertTrue(updatedStaff.getVerificationCodeExpiresAt().isAfter(LocalDateTime.now().plusMinutes(8)));
        assertEquals(0, updatedStaff.getVerificationAttemptCount());
        assertFalse(updatedStaff.isEmailVerified());
    }

    @Test
    @Transactional
    @DisplayName("Complete flow: unverified staff login triggers code -> verification succeeds -> subsequent login returns JWT")
    public void testCompleteFlow_UnverifiedStaffLoginThenVerifyThenSuccessfulLogin() throws Exception {
        String username = "flow_staff";
        String password = "temporaryPassword456";
        String email = "flow_staff@hotel.com";

        User staff = new User();
        staff.setUsername(username);
        staff.setEmail(email);
        staff.setPassword(passwordEncoder.encode(password));
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        staff.setEmailVerified(false);
        staff.setCredentialsSetupRequired(true);
        userRepository.save(staff);

        // Step 1: Initial Login with temporary credentials
        LoginRequest loginRequest = new LoginRequest(username, password);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true));

        // Retrieve the generated code by checking database hash with known dummy or setting known code
        User userAfterFirstLogin = userRepository.findByUsername(username).orElseThrow();
        assertNotNull(userAfterFirstLogin.getVerificationCodeHash());

        // For testing the verification endpoint, set a known code hash
        String knownCode = "123456";
        userAfterFirstLogin.setVerificationCodeHash(passwordEncoder.encode(knownCode));
        userRepository.save(userAfterFirstLogin);

        // Step 2: Verify code via /api/auth/email-verification/verify
        VerifyCodeRequest verifyRequest = new VerifyCodeRequest(username, knownCode);

        String verifyResponseContent = mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.setupToken").isString())
                .andReturn().getResponse().getContentAsString();
                
        VerificationResponse verifyResp = objectMapper.readValue(verifyResponseContent, VerificationResponse.class);
        String setupToken = verifyResp.getSetupToken();

        User userAfterVerification = userRepository.findByUsername(username).orElseThrow();
        assertTrue(userAfterVerification.isEmailVerified(), "User must now be email verified");
        assertNull(userAfterVerification.getVerificationCodeHash(), "Verification code hash must be cleared");

        // Step 3: Setup permanent credentials
        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken(setupToken);
        setupReq.setNewUsername("new_staff_uname");
        setupReq.setNewPassword("NewPass@123");
        
        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("new_staff_uname"));

        // Step 4: Login again -> Now must successfully return JWT!
        LoginRequest loginRequest2 = new LoginRequest("new_staff_uname", "NewPass@123");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("new_staff_uname"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false));
    }

    private void createStaffWithSetupToken(String username, String token) {
        User staff = new User();
        staff.setUsername(username);
        staff.setEmail(username + "@hotel.com");
        staff.setPassword(passwordEncoder.encode("TempPass@123"));
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        staff.setEmailVerified(true);
        staff.setCredentialsSetupRequired(true);
        staff.setSetupToken(token);
        userRepository.save(staff);
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup with password shorter than 8 chars returns 400 Bad Request")
    public void testSetupCredentials_WeakPassword_TooShort_ReturnsBadRequest() throws Exception {
        createStaffWithSetupToken("setup_short", "token-short");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-short");
        setupReq.setNewUsername("setup_short_new");
        setupReq.setNewPassword("Pass@1");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least 8 characters")));
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup missing uppercase returns 400 Bad Request")
    public void testSetupCredentials_WeakPassword_MissingUppercase_ReturnsBadRequest() throws Exception {
        createStaffWithSetupToken("setup_noupper", "token-noupper");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-noupper");
        setupReq.setNewUsername("setup_noupper_new");
        setupReq.setNewPassword("pass@1234");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("uppercase letter")));
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup missing lowercase returns 400 Bad Request")
    public void testSetupCredentials_WeakPassword_MissingLowercase_ReturnsBadRequest() throws Exception {
        createStaffWithSetupToken("setup_nolower", "token-nolower");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-nolower");
        setupReq.setNewUsername("setup_nolower_new");
        setupReq.setNewPassword("PASS@1234");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("lowercase letter")));
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup missing digit returns 400 Bad Request")
    public void testSetupCredentials_WeakPassword_MissingDigit_ReturnsBadRequest() throws Exception {
        createStaffWithSetupToken("setup_nodigit", "token-nodigit");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-nodigit");
        setupReq.setNewUsername("setup_nodigit_new");
        setupReq.setNewPassword("Password@");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("digit")));
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup missing special char returns 400 Bad Request")
    public void testSetupCredentials_WeakPassword_MissingSpecialChar_ReturnsBadRequest() throws Exception {
        createStaffWithSetupToken("setup_nospecial", "token-nospecial");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-nospecial");
        setupReq.setNewUsername("setup_nospecial_new");
        setupReq.setNewPassword("Password123");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("special character")));
    }

    @Test
    @Transactional
    @DisplayName("PUT /api/auth/credentials/setup with valid password returns 200 OK")
    public void testSetupCredentials_ValidPassword_ReturnsOk() throws Exception {
        createStaffWithSetupToken("setup_valid", "token-valid");

        SetupCredentialsRequest setupReq = new SetupCredentialsRequest();
        setupReq.setSetupToken("token-valid");
        setupReq.setNewUsername("setup_valid_new");
        setupReq.setNewPassword("ValidPass@123");

        mockMvc.perform(put("/api/auth/credentials/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setupReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.username").value("setup_valid_new"));
    }
    @Test
    @Transactional
    @DisplayName("POST /api/auth/password-reset/request with known email returns 200 OK")
    public void testPasswordResetRequest_KnownEmail_ReturnsOk() throws Exception {
        User user = new User();
        user.setUsername("reset_user");
        user.setEmail("reset@hotel.com");
        user.setPassword(passwordEncoder.encode("Pass@123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        userRepository.save(user);

        ForgotPasswordRequest req = new ForgotPasswordRequest("reset@hotel.com");
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/password-reset/request with unknown email returns 200 OK")
    public void testPasswordResetRequest_UnknownEmail_ReturnsOk() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest("unknown_reset@hotel.com");
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/password-reset/confirm with valid code returns 200 OK")
    public void testPasswordResetConfirm_ValidCode_ReturnsOk() throws Exception {
        User user = new User();
        user.setUsername("reset_user2");
        user.setEmail("reset2@hotel.com");
        user.setPassword(passwordEncoder.encode("OldPass@123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        user.setPasswordResetAttemptCount(0);
        userRepository.save(user);

        ResetPasswordRequest req = new ResetPasswordRequest("reset2@hotel.com", "123456", "NewPass@123", "NewPass@123");
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/password-reset/confirm with invalid code returns 400 Bad Request")
    public void testPasswordResetConfirm_InvalidCode_ReturnsBadRequest() throws Exception {
        User user = new User();
        user.setUsername("reset_user3");
        user.setEmail("reset3@hotel.com");
        user.setPassword(passwordEncoder.encode("OldPass@123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        user.setPasswordResetAttemptCount(0);
        userRepository.save(user);

        ResetPasswordRequest req = new ResetPasswordRequest("reset3@hotel.com", "wrong", "NewPass@123", "NewPass@123");
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/password-reset/confirm with mismatched passwords returns 400 Bad Request")
    public void testPasswordResetConfirm_PasswordMismatch_ReturnsBadRequest() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest("reset4@hotel.com", "123456", "NewPass@123", "DifferentPass@123");
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ---------------- Username or email sign-in ----------------

    @Autowired
    private JwtUtil jwtUtil;

    private void saveAccount(String username, String email, Role role, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        userRepository.save(user);
    }

    private String loginToken(String identifier, String password, String expectedUsername, Role expectedRole) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(identifier, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(expectedUsername))
                .andExpect(jsonPath("$.role").value(expectedRole.name()))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    @Transactional
    @DisplayName("Admin, Manager and Staff sign in with username or email: same identity, role and permissions in the JWT")
    public void testLogin_UsernameOrEmail_EveryRole() throws Exception {
        saveAccount("uoe_admin", "uoe_admin@hotel.com", Role.ADMIN, "adminpass123");
        saveAccount("uoe_manager", "uoe_manager@hotel.com", Role.MANAGER, "managerpass123");
        saveAccount("uoe_staff", "uoe_staff@hotel.com", Role.STAFF, "staffpass123");

        Object[][] cases = {
                {"uoe_admin", "UOE_Admin@Hotel.com", "adminpass123", Role.ADMIN, 200, 200},
                {"uoe_manager", "uoe_manager@HOTEL.com", "managerpass123", Role.MANAGER, 403, 200},
                {"uoe_staff", "uoe_staff@hotel.com", "staffpass123", Role.STAFF, 403, 200},
        };
        for (Object[] c : cases) {
            String username = (String) c[0];
            Role role = (Role) c[3];
            for (String identifier : new String[]{username, (String) c[1]}) {
                String token = loginToken(identifier, (String) c[2], username, role);
                assertTrue(jwtUtil.validateToken(token), identifier + ": valid JWT");
                assertEquals(username, jwtUtil.extractUsername(token), identifier + ": token belongs to the account, not the typed email");
                assertEquals(role.name(), jwtUtil.extractRole(token), identifier + ": same role");
                // Existing authorization rules apply unchanged to either token
                mockMvc.perform(get("/api/test/admin").header("Authorization", "Bearer " + token)).andExpect(status().is((int) c[4]));
                mockMvc.perform(get("/api/test/staff").header("Authorization", "Bearer " + token)).andExpect(status().is((int) c[5]));
            }
        }
    }

    @Test
    @Transactional
    @DisplayName("Unverified Staff signing in by email gets the email-verification flow, not a JWT")
    public void testLogin_UnverifiedStaffByEmail_TriggersVerification() throws Exception {
        User staff = new User();
        staff.setUsername("uoe_unverified_staff");
        staff.setEmail("uoe_unverified_staff@hotel.com");
        staff.setPassword(passwordEncoder.encode("tempPass123"));
        staff.setRole(Role.STAFF);
        staff.setActive(true);
        staff.setEmailVerified(false);
        userRepository.save(staff);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("UOE_Unverified_Staff@hotel.com", "tempPass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.username").value("uoe_unverified_staff"))
                .andExpect(jsonPath("$.email").value("uoe_unverified_staff@hotel.com"));

        User reloaded = userRepository.findByUsername("uoe_unverified_staff").orElseThrow();
        assertNotNull(reloaded.getVerificationCodeHash(), "A verification code was issued for the account found by email");
        assertFalse(reloaded.isEmailVerified());
    }

    @Test
    @Transactional
    @DisplayName("Email sign-in rejects a wrong password, an unknown email, and another account's password with 401")
    public void testLogin_Email_Failures() throws Exception {
        saveAccount("uoe_fail_admin", "uoe_fail_admin@hotel.com", Role.ADMIN, "adminpass123");
        saveAccount("uoe_fail_staff", "uoe_fail_staff@hotel.com", Role.STAFF, "staffpass123");

        for (LoginRequest request : new LoginRequest[]{
                new LoginRequest("uoe_fail_admin@hotel.com", "wrongpass"),
                new LoginRequest("uoe_fail_admin", "wrongpass"),
                new LoginRequest("nobody_here@hotel.com", "adminpass123"),
                new LoginRequest("uoe_fail_admin@hotel.com", "staffpass123")}) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
    }
}
