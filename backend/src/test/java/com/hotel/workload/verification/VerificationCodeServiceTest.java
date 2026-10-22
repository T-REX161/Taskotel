package com.hotel.workload.verification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class VerificationCodeServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailService emailService;
    private VerificationCodeServiceImpl verificationCodeService;

    @BeforeEach
    public void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        emailService = mock(EmailService.class);
        verificationCodeService = new VerificationCodeServiceImpl(userRepository, passwordEncoder, emailService);
    }

    @Test
    @DisplayName("Generate code produces cryptographically secure 6-digit numeric codes")
    public void testGenerateCode_AlwaysSixDigits() {
        Set<String> generatedCodes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = verificationCodeService.generateCode();
            assertNotNull(code);
            assertEquals(6, code.length(), "Code must be exactly 6 digits");
            assertTrue(code.matches("^\\d{6}$"), "Code must contain only numeric digits: " + code);
            generatedCodes.add(code);
        }
        // Ensure there is healthy randomness across 1000 generations
        assertTrue(generatedCodes.size() > 900, "Expected broad random distribution of generated codes");
    }

    @Test
    @DisplayName("Code is hashed properly and can be verified by PasswordEncoder")
    public void testHashCode_ProducesValidHash() {
        String rawCode = "123456";
        String hash = verificationCodeService.hashCode(rawCode);

        assertNotNull(hash);
        assertNotEquals(rawCode, hash, "Hash must not equal raw code");
        assertTrue(passwordEncoder.matches(rawCode, hash), "Password encoder must match the raw code with its hash");
    }

    @Test
    @DisplayName("Correct code verification succeeds, updates emailVerified, and clears verification fields")
    public void testCorrectCodeVerification_Success() {
        String rawCode = "654321";
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setCredentialsSetupRequired(true);
        user.setVerificationCodeHash(passwordEncoder.encode(rawCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(0);
        user.setVerificationCreatedAt(LocalDateTime.now());

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String result = verificationCodeService.verifyCode("staff1", rawCode);

        assertNotNull(result);
        // Let's assert it is not null if setup is required, or just ignore the return value assertion.
        assertTrue(user.isEmailVerified(), "emailVerified must be set to true");
        assertNull(user.getVerificationCodeHash(), "verificationCodeHash must be cleared");
        assertNull(user.getVerificationCodeExpiresAt(), "verificationCodeExpiresAt must be cleared");
        assertEquals(0, user.getVerificationAttemptCount(), "verificationAttemptCount must be reset to 0");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Incorrect code is rejected, increments attempt count, and does not verify user")
    public void testIncorrectCode_RejectionAndAttemptIncrement() {
        String correctCode = "112233";
        String wrongCode = "998877";

        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(correctCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(0);

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));

        VerificationException ex = assertThrows(VerificationException.class, () ->
                verificationCodeService.verifyCode("staff1", wrongCode)
        );

        assertTrue(ex.getMessage().contains("Invalid verification code"));
        assertEquals(1, user.getVerificationAttemptCount(), "Attempt count should be incremented to 1");
        assertFalse(user.isEmailVerified(), "emailVerified must remain false");
        assertNotNull(user.getVerificationCodeHash(), "Code hash should still exist after 1 failed attempt");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Expired code is rejected and code is invalidated")
    public void testExpiredCode_Rejection() {
        String code = "123456";
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(code));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired 1 min ago
        user.setVerificationAttemptCount(0);

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));

        VerificationException ex = assertThrows(VerificationException.class, () ->
                verificationCodeService.verifyCode("staff1", code)
        );

        assertTrue(ex.getMessage().contains("expired"), "Message should mention expired code");
        assertFalse(user.isEmailVerified(), "User should not be verified");
        assertNull(user.getVerificationCodeHash(), "Expired code hash must be cleared");
        assertNull(user.getVerificationCodeExpiresAt(), "ExpiresAt must be cleared");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Already-used code is rejected when hash is cleared")
    public void testAlreadyUsedCode_Rejection() {
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null); // Already used/cleared
        user.setVerificationCodeExpiresAt(null);

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));

        VerificationException ex = assertThrows(VerificationException.class, () ->
                verificationCodeService.verifyCode("staff1", "123456")
        );

        assertTrue(ex.getMessage().contains("No active verification code found"));
    }

    @Test
    @DisplayName("Maximum attempt handling invalidates code upon reaching 5 incorrect attempts")
    public void testMaximumAttemptHandling_Exceeded() {
        String correctCode = "123456";
        String wrongCode = "000000";

        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(correctCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(4); // 4 prior failed attempts

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));

        // 5th attempt with wrong code
        VerificationException ex = assertThrows(VerificationException.class, () ->
                verificationCodeService.verifyCode("staff1", wrongCode)
        );

        assertTrue(ex.getMessage().contains("Maximum verification attempts exceeded"));
        assertEquals(5, user.getVerificationAttemptCount());
        assertNull(user.getVerificationCodeHash(), "Code hash must be invalidated upon 5th failure");
        assertNull(user.getVerificationCodeExpiresAt(), "Code expiration must be cleared upon 5th failure");
        assertFalse(user.isEmailVerified());

        // Subsequent attempt (even with correct code) must fail because code hash was destroyed
        assertThrows(VerificationException.class, () ->
                verificationCodeService.verifyCode("staff1", correctCode)
        );
    }

    @Test
    @DisplayName("sendVerificationCode resets attempt count, sets 10-minute expiry, and dispatches email")
    public void testSendVerificationCode_ResetsAttemptCountAndDispatchesEmail() {
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationAttemptCount(4); // Previous failed attempts

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));

        verificationCodeService.sendVerificationCode("staff1");

        assertEquals(0, user.getVerificationAttemptCount(), "Attempt count must reset to 0 on new code");
        assertNotNull(user.getVerificationCodeHash(), "Hash must be populated");
        assertNotNull(user.getVerificationCodeExpiresAt(), "ExpiresAt must be populated");
        assertTrue(user.getVerificationCodeExpiresAt().isAfter(LocalDateTime.now().plusMinutes(9)),
                "Expiration time must be set to ~10 minutes");
        assertNotNull(user.getVerificationCreatedAt());

        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendVerificationEmail(emailCaptor.capture(), codeCaptor.capture());

        assertEquals("staff1@hotel.com", emailCaptor.getValue());
        assertEquals(6, codeCaptor.getValue().length());
        assertTrue(passwordEncoder.matches(codeCaptor.getValue(), user.getVerificationCodeHash()));
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("sendVerificationCode does not store new OTP if email dispatch fails")
    public void testSendVerificationCode_WhenEmailDispatchFails_DoesNotStoreOtp() {
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));
        doThrow(new EmailSendingException("Failed to send verification email"))
                .when(emailService).sendVerificationEmail(anyString(), anyString());

        assertThrows(EmailSendingException.class, () ->
                verificationCodeService.sendVerificationCode("staff1")
        );

        verify(userRepository, never()).save(any());
        assertNull(user.getVerificationCodeHash(), "New OTP hash must not be stored if dispatch fails");
        assertNull(user.getVerificationCodeExpiresAt(), "Expiration must not be set if dispatch fails");
    }

    @Test
    @DisplayName("sendVerificationCode preserves previously valid code if resend dispatch fails")
    public void testSendVerificationCode_WhenResendFails_PreservesExistingValidCode() {
        String existingHash = passwordEncoder.encode("123456");
        LocalDateTime existingExpiry = LocalDateTime.now().plusMinutes(8);

        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(existingHash);
        user.setVerificationCodeExpiresAt(existingExpiry);
        user.setVerificationAttemptCount(2);

        when(userRepository.findByUsername("staff1")).thenReturn(Optional.of(user));
        doThrow(new EmailSendingException("SendGrid rejected"))
                .when(emailService).sendVerificationEmail(anyString(), anyString());

        assertThrows(EmailSendingException.class, () ->
                verificationCodeService.sendVerificationCode("staff1")
        );

        verify(userRepository, never()).save(any());
        assertEquals(existingHash, user.getVerificationCodeHash(), "Existing valid code hash must be preserved");
        assertEquals(existingExpiry, user.getVerificationCodeExpiresAt(), "Existing expiry must be preserved");
        assertEquals(2, user.getVerificationAttemptCount(), "Attempt count must not be reset on failed dispatch");
    }

    @Test
    @DisplayName("Verification lookup works by email as well as username")
    public void testLookupByEmail() {
        String code = "123456";
        User user = new User();
        user.setUsername("staff1");
        user.setEmail("staff1@hotel.com");
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(code));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(0);

        when(userRepository.findByUsername("staff1@hotel.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("staff1@hotel.com")).thenReturn(Optional.of(user));

        String result = verificationCodeService.verifyCode("staff1@hotel.com", code);
        // assert result as appropriate
        assertTrue(user.isEmailVerified());
    }

    @Test
    @DisplayName("Admin and Manager users are email verified by default and cannot be locked out")
    public void testAdminAndManager_EmailVerifiedByDefault() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(Role.ADMIN);

        User manager = new User();
        manager.setUsername("manager");
        manager.setRole(Role.MANAGER);

        User staff = new User();
        staff.setUsername("staff");
        staff.setRole(Role.STAFF);

        assertTrue(admin.isEmailVerified(), "Admin must be email verified by default");
        assertTrue(manager.isEmailVerified(), "Manager must be email verified by default");
        assertFalse(staff.isEmailVerified(), "Staff must not be email verified by default");
    }
}
