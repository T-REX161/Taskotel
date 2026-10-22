package com.hotel.workload.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class EmailVerificationControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private EmailService emailService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        org.mockito.Mockito.when(emailService.isConfigured()).thenReturn(true);
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/send dispatches code and does not expose code in response")
    public void testSendVerificationCode_WithIdentifierInBody_Success() throws Exception {
        User user = new User();
        user.setUsername("teststaff_send");
        user.setEmail("teststaff_send@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        userRepository.save(user);

        SendVerificationRequest request = new SendVerificationRequest("teststaff_send");

        MvcResult result = mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Verification code sent successfully to email"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.verificationCode").doesNotExist())
                .andExpect(jsonPath("$.verificationCodeHash").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.toLowerCase().contains("hash"));

        User updatedUser = userRepository.findByUsername("teststaff_send").orElseThrow();
        assertNotNull(updatedUser.getVerificationCodeHash(), "Hash must be saved in database");
        assertNotNull(updatedUser.getVerificationCodeExpiresAt());
        assertEquals(0, updatedUser.getVerificationAttemptCount());

        org.mockito.Mockito.verify(emailService, org.mockito.Mockito.times(1))
                .sendVerificationEmail(org.mockito.ArgumentMatchers.eq("teststaff_send@hotel.com"),
                        org.mockito.ArgumentMatchers.matches("^\\d{6}$"));
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/send returns 500 and does not retain active OTP on email failure")
    public void testSendVerificationCode_EmailServiceFails_Returns500AndDoesNotStoreOtp() throws Exception {
        User user = new User();
        user.setUsername("teststaff_sendfail");
        user.setEmail("teststaff_sendfail@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        userRepository.save(user);

        org.mockito.Mockito.doThrow(new EmailSendingException("SendGrid rejected request"))
                .when(emailService).sendVerificationEmail(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        SendVerificationRequest request = new SendVerificationRequest("teststaff_sendfail");

        MvcResult result = mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Failed to send verification email"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.verificationCode").doesNotExist())
                .andExpect(jsonPath("$.verificationCodeHash").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.toLowerCase().contains("stacktrace"));
        assertFalse(responseBody.toLowerCase().contains("sendgrid"));

        User reloadedUser = userRepository.findByUsername("teststaff_sendfail").orElseThrow();
        assertNull(reloadedUser.getVerificationCodeHash(), "Database must not retain an active OTP hash when email sending fails");
        assertNull(reloadedUser.getVerificationCodeExpiresAt());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/send preserves existing valid OTP when resend dispatch fails")
    public void testSendVerificationCode_ResendFails_PreservesExistingCode() throws Exception {
        String existingCode = "123456";
        User user = new User();
        user.setUsername("teststaff_resendfail");
        user.setEmail("teststaff_resendfail@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(existingCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(8));
        user.setVerificationAttemptCount(1);
        userRepository.save(user);

        String originalHash = user.getVerificationCodeHash();

        org.mockito.Mockito.doThrow(new EmailSendingException("Connection timeout"))
                .when(emailService).sendVerificationEmail(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        SendVerificationRequest request = new SendVerificationRequest("teststaff_resendfail");

        mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Failed to send verification email"));

        User reloadedUser = userRepository.findByUsername("teststaff_resendfail").orElseThrow();
        assertEquals(originalHash, reloadedUser.getVerificationCodeHash(), "Previously valid OTP hash must be preserved");
        assertEquals(1, reloadedUser.getVerificationAttemptCount());
    }

    @Test
    @Transactional
    @WithMockUser(username = "teststaff_auth")
    @DisplayName("POST /api/auth/email-verification/send supports authenticated user identity via Principal")
    public void testSendVerificationCode_WithAuthenticatedPrincipal() throws Exception {
        User user = new User();
        user.setUsername("teststaff_auth");
        user.setEmail("teststaff_auth@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").doesNotExist());

        User updatedUser = userRepository.findByUsername("teststaff_auth").orElseThrow();
        assertNotNull(updatedUser.getVerificationCodeHash());
    }

    @Test
    @DisplayName("POST /api/auth/email-verification/send returns 400 when identifier is missing")
    public void testSendVerificationCode_MissingIdentifier_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/auth/email-verification/send returns 404 when user is not found")
    public void testSendVerificationCode_UserNotFound_ReturnsNotFound() throws Exception {
        SendVerificationRequest request = new SendVerificationRequest("unknown_user");

        mockMvc.perform(post("/api/auth/email-verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/verify succeeds with correct code and updates database")
    public void testVerifyCode_CorrectCode_Success() throws Exception {
        String plainCode = "654321";
        User user = new User();
        user.setUsername("teststaff_verify");
        user.setEmail("teststaff_verify@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(plainCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(0);
        userRepository.save(user);

        VerifyCodeRequest request = new VerifyCodeRequest("teststaff_verify", plainCode);

        MvcResult result = mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Email verification successful"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.verificationCodeHash").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.contains(plainCode), "Plain code must never appear in response");

        User updatedUser = userRepository.findByUsername("teststaff_verify").orElseThrow();
        assertTrue(updatedUser.isEmailVerified(), "emailVerified must be true");
        assertNull(updatedUser.getVerificationCodeHash(), "verificationCodeHash must be cleared");
        assertNull(updatedUser.getVerificationCodeExpiresAt(), "verificationCodeExpiresAt must be cleared");
        assertEquals(0, updatedUser.getVerificationAttemptCount());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/verify rejects incorrect code with 400 Bad Request")
    public void testVerifyCode_IncorrectCode_ReturnsBadRequest() throws Exception {
        String correctCode = "123456";
        String wrongCode = "999999";

        User user = new User();
        user.setUsername("teststaff_wrong");
        user.setEmail("teststaff_wrong@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(correctCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(0);
        userRepository.save(user);

        VerifyCodeRequest request = new VerifyCodeRequest("teststaff_wrong", wrongCode);

        mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("Invalid verification code")))
                .andExpect(jsonPath("$.code").doesNotExist());

        User updatedUser = userRepository.findByUsername("teststaff_wrong").orElseThrow();
        assertEquals(1, updatedUser.getVerificationAttemptCount());
        assertFalse(updatedUser.isEmailVerified());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/verify rejects expired code")
    public void testVerifyCode_ExpiredCode_ReturnsBadRequest() throws Exception {
        String code = "123456";

        User user = new User();
        user.setUsername("teststaff_expired");
        user.setEmail("teststaff_expired@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(code));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(2)); // Expired
        user.setVerificationAttemptCount(0);
        userRepository.save(user);

        VerifyCodeRequest request = new VerifyCodeRequest("teststaff_expired", code);

        mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("expired")))
                .andExpect(jsonPath("$.code").doesNotExist());

        User updatedUser = userRepository.findByUsername("teststaff_expired").orElseThrow();
        assertNull(updatedUser.getVerificationCodeHash(), "Expired code hash must be cleared");
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/verify rejects already-used code")
    public void testVerifyCode_AlreadyUsedCode_ReturnsBadRequest() throws Exception {
        User user = new User();
        user.setUsername("teststaff_used");
        user.setEmail("teststaff_used@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(true);
        user.setVerificationCodeHash(null); // Already used/cleared
        userRepository.save(user);

        VerifyCodeRequest request = new VerifyCodeRequest("teststaff_used", "123456");

        mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("No active verification code found")))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @Transactional
    @DisplayName("POST /api/auth/email-verification/verify enforces maximum attempt limit")
    public void testVerifyCode_MaxAttemptsExceeded() throws Exception {
        String correctCode = "123456";
        String wrongCode = "000000";

        User user = new User();
        user.setUsername("teststaff_maxattempts");
        user.setEmail("teststaff_maxattempts@hotel.com");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.STAFF);
        user.setEmailVerified(false);
        user.setVerificationCodeHash(passwordEncoder.encode(correctCode));
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setVerificationAttemptCount(4); // 4 existing failed attempts
        userRepository.save(user);

        VerifyCodeRequest request = new VerifyCodeRequest("teststaff_maxattempts", wrongCode);

        // 5th attempt
        mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("Maximum verification attempts exceeded")))
                .andExpect(jsonPath("$.code").doesNotExist());

        User updatedUser = userRepository.findByUsername("teststaff_maxattempts").orElseThrow();
        assertNull(updatedUser.getVerificationCodeHash(), "Code must be invalidated after 5th failed attempt");
    }

    @Test
    @DisplayName("POST /api/auth/email-verification/verify validates 6-digit code format")
    public void testVerifyCode_InvalidFormat_ReturnsBadRequest() throws Exception {
        VerifyCodeRequest request = new VerifyCodeRequest("anyuser", "123"); // Not 6 digits

        mockMvc.perform(post("/api/auth/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
