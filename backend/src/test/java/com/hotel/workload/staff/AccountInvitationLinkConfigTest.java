package com.hotel.workload.staff;

import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The login link is configuration (taskotel.frontend-base-url, set from FRONTEND_BASE_URL), never hard-coded. */
@SpringBootTest
@TestPropertySource(properties = "taskotel.frontend-base-url=https://hotel.example.org//")
public class AccountInvitationLinkConfigTest {

    @Autowired private AccountInvitationService service;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("A configured address is used, with the login path appended and the trailing slashes removed")
    public void configuredAddressIsUsed() {
        assertEquals("https://hotel.example.org/login", service.loginUrl());
        when(emailService.isConfigured()).thenReturn(true);
        service.send("a@hotel.test", "a.user", "Tmp@Pass1234xYz");
        verify(emailService).sendAccountInvitationEmail("a@hotel.test", "a.user", "Tmp@Pass1234xYz", "https://hotel.example.org/login");
    }
}
