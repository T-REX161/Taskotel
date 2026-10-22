package com.hotel.workload.security;

import com.hotel.workload.verification.VerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private VerificationCodeService verificationCodeService;
    private PasswordValidatorService passwordValidatorService;
    private AuthService authService;

    @BeforeEach
    public void setup() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        jwtUtil = mock(JwtUtil.class);
        verificationCodeService = mock(VerificationCodeService.class);
        passwordValidatorService = new PasswordValidatorService();
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil, verificationCodeService, passwordValidatorService);
    }

    @Test
    @DisplayName("Valid Admin login returns JWT and does not send verification email")
    public void testSuccessfulAdminLogin() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.ADMIN);
        user.setActive(true);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("admin", Role.ADMIN)).thenReturn("mock-token");

        LoginRequest request = new LoginRequest("admin", "password");
        LoginResponse response = authService.authenticate(request);

        assertNotNull(response);
        assertEquals("mock-token", response.getToken());
        assertEquals("admin", response.getUsername());
        assertEquals(Role.ADMIN, response.getRole());
        assertFalse(response.isEmailVerificationRequired());
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, times(1)).generateToken("admin", Role.ADMIN);
        
        assertFalse(user.isFirstLogin());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Valid Manager login returns JWT and does not send verification email")
    public void testSuccessfulManagerLogin() {
        User user = new User();
        user.setUsername("manager1");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.MANAGER);
        user.setActive(true);

        when(userRepository.findByUsername("manager1")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("manager1", Role.MANAGER)).thenReturn("manager-token");

        LoginRequest request = new LoginRequest("manager1", "password");
        LoginResponse response = authService.authenticate(request);

        assertNotNull(response);
        assertEquals("manager-token", response.getToken());
        assertEquals("manager1", response.getUsername());
        assertEquals(Role.MANAGER, response.getRole());
        assertFalse(response.isEmailVerificationRequired());
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, times(1)).generateToken("manager1", Role.MANAGER);

        assertFalse(user.isFirstLogin());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Valid verified Staff login returns JWT and does not send verification email")
    public void testSuccessfulVerifiedStaffLogin() {
        User user = new User();
        user.setUsername("staff_verified");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        user.setActive(true);

        when(userRepository.findByUsername("staff_verified")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("staff_verified", Role.STAFF)).thenReturn("staff-token");

        LoginRequest request = new LoginRequest("staff_verified", "password");
        LoginResponse response = authService.authenticate(request);

        assertNotNull(response);
        assertEquals("staff-token", response.getToken());
        assertEquals("staff_verified", response.getUsername());
        assertEquals(Role.STAFF, response.getRole());
        assertFalse(response.isEmailVerificationRequired());
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, times(1)).generateToken("staff_verified", Role.STAFF);

        assertFalse(user.isFirstLogin());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Subsequent logins do not overwrite firstLogin or save user unnecessarily")
    public void testSubsequentLoginLeavesFirstLoginFalse() {
        User user = new User();
        user.setUsername("returning");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.ADMIN);
        user.setActive(true);
        user.setFirstLogin(false); // already false

        when(userRepository.findByUsername("returning")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("returning", Role.ADMIN)).thenReturn("mock-token");

        LoginRequest request = new LoginRequest("returning", "password");
        authService.authenticate(request);

        assertFalse(user.isFirstLogin());
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("Valid unverified Staff login does NOT return JWT, triggers verification email, and indicates verification required")
    public void testUnverifiedStaffLogin_TriggersVerificationAndDoesNotReturnJwt() {
        User user = new User();
        user.setUsername("staff_unverified");
        user.setEmail("staff_unverified@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setActive(true);

        when(userRepository.findByUsername("staff_unverified")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("staff_unverified", "password");
        LoginResponse response = authService.authenticate(request);

        assertNotNull(response);
        assertNull(response.getToken(), "Unverified Staff login must NEVER issue or return a JWT");
        assertEquals("staff_unverified", response.getUsername());
        assertEquals("staff_unverified@hotel.com", response.getEmail());
        assertEquals(Role.STAFF, response.getRole());
        assertTrue(response.isEmailVerificationRequired(), "emailVerificationRequired must be true");
        assertEquals(Boolean.FALSE, response.getEmailVerified());
        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("Email verification required"));

        // Verify verification code dispatch was triggered
        verify(verificationCodeService, times(1)).sendVerificationCode("staff_unverified");
        // Verify JWT generation was NEVER invoked
        verify(jwtUtil, never()).generateToken(any(), any());
        
        // Verify user firstLogin was not updated
        assertTrue(user.isFirstLogin());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Invalid password preserves invalid-login behavior and does NOT send email")
    public void testInvalidPassword() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("password"));
        user.setActive(true);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("admin", "wrongpassword");

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, never()).generateToken(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Unknown username preserves invalid-login behavior and does NOT send email")
    public void testUnknownUsername() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("unknown", "password");

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, never()).generateToken(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Inactive user preserves invalid-login behavior and does NOT send email")
    public void testInactiveUser() {
        User user = new User();
        user.setUsername("inactiveuser");
        user.setPassword(passwordEncoder.encode("password"));
        user.setActive(false);

        when(userRepository.findByUsername("inactiveuser")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest("inactiveuser", "password");

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));
        assertEquals("User account is disabled", ex.getMessage());
        verify(verificationCodeService, never()).sendVerificationCode(any());
        verify(jwtUtil, never()).generateToken(any(), any());
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "nouppercase@1",
            "NOLOWERCASE@1",
            "NoDigitsHere!",
            "NoSpecialChar1",
            "Has Space@123"
    })
    @DisplayName("setupCredentials rejects weak new password with InvalidPasswordException")
    public void testSetupCredentials_WeakPassword_ThrowsException(String weakPassword) {
        User user = new User();
        user.setUsername("pending_user");
        user.setRole(Role.STAFF);
        user.setCredentialsSetupRequired(true);
        user.setSetupToken("token123");

        when(userRepository.findBySetupToken("token123")).thenReturn(Optional.of(user));

        SetupCredentialsRequest request = new SetupCredentialsRequest();
        request.setSetupToken("token123");
        request.setNewUsername("new_username");
        request.setNewPassword(weakPassword);

        assertThrows(InvalidPasswordException.class, () -> authService.setupCredentials(request));
        verify(userRepository, never()).save(any());
        verify(jwtUtil, never()).generateToken(any(), any());
    }

    @Test
    @DisplayName("setupCredentials succeeds when new password satisfies security rules")
    public void testSetupCredentials_ValidPassword_Success() {
        User user = new User();
        user.setUsername("pending_user");
        user.setRole(Role.STAFF);
        user.setCredentialsSetupRequired(true);
        user.setSetupToken("token123");

        when(userRepository.findBySetupToken("token123")).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("new_username")).thenReturn(false);
        when(jwtUtil.generateToken("new_username", Role.STAFF)).thenReturn("new-jwt-token");

        SetupCredentialsRequest request = new SetupCredentialsRequest();
        request.setSetupToken("token123");
        request.setNewUsername("new_username");
        request.setNewPassword("SecurePass@123");

        LoginResponse response = authService.setupCredentials(request);

        assertNotNull(response);
        assertEquals("new-jwt-token", response.getToken());
        assertEquals("new_username", response.getUsername());
        assertFalse(user.isCredentialsSetupRequired());
        assertNull(user.getSetupToken());
        assertFalse(user.isFirstLogin()); // Ensure setupCredentials updates this
        verify(userRepository, times(1)).save(user);
    }
}

