package com.hotel.workload.security;

import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PasswordResetServiceTest {

    private UserRepository userRepository;
    private EmailService emailService;
    private PasswordEncoder passwordEncoder;
    private PasswordValidatorService passwordValidatorService;
    private PasswordResetService passwordResetService;

    @BeforeEach
    public void setup() {
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        passwordValidatorService = new PasswordValidatorService();
        passwordResetService = new PasswordResetService(userRepository, emailService, passwordEncoder, passwordValidatorService);
        when(emailService.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("requestReset does nothing for unknown email")
    public void testRequestReset_UnknownEmail_DoesNothing() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        ForgotPasswordRequest request = new ForgotPasswordRequest("unknown@test.com");
        passwordResetService.requestReset(request);

        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    @DisplayName("requestReset does nothing for inactive user")
    public void testRequestReset_InactiveUser_DoesNothing() {
        User user = new User();
        user.setEmail("inactive@test.com");
        user.setActive(false);

        when(userRepository.findByEmail("inactive@test.com")).thenReturn(Optional.of(user));

        ForgotPasswordRequest request = new ForgotPasswordRequest("inactive@test.com");
        passwordResetService.requestReset(request);

        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    @DisplayName("requestReset generates code, saves, and sends email for active user")
    public void testRequestReset_ActiveUser_Success() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setActive(true);

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ForgotPasswordRequest request = new ForgotPasswordRequest("active@test.com");
        passwordResetService.requestReset(request);

        verify(userRepository, times(1)).save(user);
        
        assertNotNull(user.getPasswordResetCodeHash());
        assertNotNull(user.getPasswordResetExpiresAt());
        assertNotNull(user.getPasswordResetLastSentAt());
        assertEquals(0, user.getPasswordResetAttemptCount());

        verify(emailService, times(1)).sendPasswordResetEmail(eq("active@test.com"), anyString());
    }

    @Test
    @DisplayName("requestReset respects 60-second cooldown")
    public void testRequestReset_CooldownActive_DoesNothing() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setActive(true);
        user.setPasswordResetLastSentAt(LocalDateTime.now().minusSeconds(30)); // 30s ago

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ForgotPasswordRequest request = new ForgotPasswordRequest("active@test.com");
        passwordResetService.requestReset(request);

        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    @DisplayName("requestReset throws when email service is unconfigured")
    public void testRequestReset_EmailServiceUnconfigured_ThrowsException() {
        when(emailService.isConfigured()).thenReturn(false);

        ForgotPasswordRequest request = new ForgotPasswordRequest("active@test.com");
        assertThrows(EmailSendingException.class, () -> passwordResetService.requestReset(request));
    }

    @Test
    @DisplayName("requestReset propagates email sending exception")
    public void testRequestReset_EmailServiceThrowsException_Propagates() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setActive(true);

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));
        doThrow(new EmailSendingException("Email failed")).when(emailService).sendPasswordResetEmail(any(), any());

        ForgotPasswordRequest request = new ForgotPasswordRequest("active@test.com");
        assertThrows(EmailSendingException.class, () -> passwordResetService.requestReset(request));

        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("confirmReset throws for unknown email")
    public void testConfirmReset_UnknownEmail_ThrowsException() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        ResetPasswordRequest request = new ResetPasswordRequest("unknown@test.com", "123456", "NewPass@123", "NewPass@123");
        assertThrows(InvalidVerificationCodeException.class, () -> passwordResetService.confirmReset(request));
    }

    @Test
    @DisplayName("confirmReset throws for expired code")
    public void testConfirmReset_ExpiredCode_ThrowsException() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest("active@test.com", "123456", "NewPass@123", "NewPass@123");
        assertThrows(InvalidVerificationCodeException.class, () -> passwordResetService.confirmReset(request));
    }

    @Test
    @DisplayName("confirmReset increments attempts and throws for invalid code")
    public void testConfirmReset_InvalidCode_IncrementsAttemptsAndThrows() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        user.setPasswordResetAttemptCount(0);

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest("active@test.com", "654321", "NewPass@123", "NewPass@123");
        assertThrows(InvalidVerificationCodeException.class, () -> passwordResetService.confirmReset(request));

        assertEquals(1, user.getPasswordResetAttemptCount());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("confirmReset clears fields when max attempts reached")
    public void testConfirmReset_MaxAttemptsReached_ClearsFieldsAndThrows() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        user.setPasswordResetAttemptCount(4); // Next failure will be 5th

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest("active@test.com", "654321", "NewPass@123", "NewPass@123");
        assertThrows(InvalidVerificationCodeException.class, () -> passwordResetService.confirmReset(request));

        assertEquals(0, user.getPasswordResetAttemptCount());
        assertNull(user.getPasswordResetCodeHash());
        assertNull(user.getPasswordResetExpiresAt());
        assertNull(user.getPasswordResetLastSentAt());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("confirmReset updates password and clears fields on success")
    public void testConfirmReset_Success() {
        User user = new User();
        user.setEmail("active@test.com");
        user.setPassword(passwordEncoder.encode("oldPassword"));
        user.setPasswordResetCodeHash(passwordEncoder.encode("123456"));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(5));
        user.setPasswordResetAttemptCount(2);
        user.setPasswordResetLastSentAt(LocalDateTime.now());

        when(userRepository.findByEmail("active@test.com")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest("active@test.com", "123456", "NewPass@123", "NewPass@123");
        passwordResetService.confirmReset(request);

        assertTrue(passwordEncoder.matches("NewPass@123", user.getPassword()));
        assertEquals(0, user.getPasswordResetAttemptCount());
        assertNull(user.getPasswordResetCodeHash());
        assertNull(user.getPasswordResetExpiresAt());
        assertNull(user.getPasswordResetLastSentAt());
        verify(userRepository, times(1)).save(user);
    }
}
