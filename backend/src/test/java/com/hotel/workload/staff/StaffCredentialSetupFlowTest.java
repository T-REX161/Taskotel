package com.hotel.workload.staff;

import com.hotel.workload.security.AuthService;
import com.hotel.workload.security.LoginResponse;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.SetupCredentialsRequest;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.VerificationCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class StaffCredentialSetupFlowTest {

    @Autowired
    private StaffService staffService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Verify full credential setup lifecycle for newly created staff")
    public void testStaffCreationAndCredentialSetupFlow() {
        // 1. Create Staff via StaffService.createStaff
        CreateStaffRequest request = new CreateStaffRequest();
        request.setFullName("John Setup");
        request.setEmail("john.setup@hotel.com");
        request.setPhone("1234567890");
        request.setDepartment(Department.FRONT_DESK);
        request.setAvailability(Availability.AVAILABLE);
        request.setUsername("johnsetup");
        request.setTemporaryPassword("TempPass@123");

        StaffDto staffDto = staffService.createStaff(request);
        assertNotNull(staffDto);
        assertEquals("johnsetup", staffDto.getUsername());

        // Prove requirement 6:
        // newly created Staff has emailVerified = false and credentialsSetupRequired = true
        User createdUser = userRepository.findByUsername("johnsetup").orElseThrow();
        assertFalse(createdUser.isEmailVerified(), "Newly created Staff must have emailVerified = false");
        assertTrue(createdUser.isCredentialsSetupRequired(), "Newly created Staff must have credentialsSetupRequired = true");
        assertEquals(Role.STAFF, createdUser.getRole());

        // 2. Set up verification code for johnsetup
        String rawCode = "654321";
        createdUser.setVerificationCodeHash(passwordEncoder.encode(rawCode));
        createdUser.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
        userRepository.save(createdUser);

        // Prove requirement 6:
        // successful OTP verification for a newly created Staff returns a non-null setupToken
        String setupToken = verificationCodeService.verifyCode("johnsetup", rawCode);
        assertNotNull(setupToken, "Verification of newly created staff with credentialsSetupRequired=true must return setupToken");
        assertFalse(setupToken.isBlank(), "setupToken must not be blank");

        User userAfterVerification = userRepository.findByUsername("johnsetup").orElseThrow();
        assertTrue(userAfterVerification.isEmailVerified(), "Staff user must be email verified after OTP verification");
        assertEquals(setupToken, userAfterVerification.getSetupToken(), "Stored setupToken must match returned token");

        // 3. Complete credential setup via AuthService.setupCredentials
        SetupCredentialsRequest setupRequest = new SetupCredentialsRequest();
        setupRequest.setSetupToken(setupToken);
        setupRequest.setNewUsername("john_permanent");
        setupRequest.setNewPassword("PermPass@123");

        LoginResponse loginResponse = authService.setupCredentials(setupRequest);
        assertNotNull(loginResponse);
        assertNotNull(loginResponse.getToken());
        assertEquals("john_permanent", loginResponse.getUsername());

        // Prove requirement 6:
        // after credential setup, credentialsSetupRequired becomes false and emailVerified remains true
        User userAfterSetup = userRepository.findByUsername("john_permanent").orElseThrow();
        assertFalse(userAfterSetup.isCredentialsSetupRequired(), "credentialsSetupRequired must become false after setup");
        assertTrue(userAfterSetup.isEmailVerified(), "emailVerified must remain true after credential setup");
        assertNull(userAfterSetup.getSetupToken(), "setupToken must be cleared after credential setup");
    }

    @Test
    @DisplayName("Verify Admin and Manager accounts are not required to do credentials setup")
    public void testAdminAndManagerDoNotRequireCredentialsSetup() {
        User admin = new User();
        admin.setUsername("admin_test_user");
        admin.setEmail("admin_test@hotel.com");
        admin.setPassword(passwordEncoder.encode("AdminPass@123"));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        userRepository.save(admin);

        User savedAdmin = userRepository.findByUsername("admin_test_user").orElseThrow();
        assertTrue(savedAdmin.isEmailVerified(), "Admin must have emailVerified = true");
        assertFalse(savedAdmin.isCredentialsSetupRequired(), "Admin must have credentialsSetupRequired = false");

        User manager = new User();
        manager.setUsername("manager_test_user");
        manager.setEmail("manager_test@hotel.com");
        manager.setPassword(passwordEncoder.encode("ManagerPass@123"));
        manager.setRole(Role.MANAGER);
        manager.setActive(true);
        userRepository.save(manager);

        User savedManager = userRepository.findByUsername("manager_test_user").orElseThrow();
        assertTrue(savedManager.isEmailVerified(), "Manager must have emailVerified = true");
        assertFalse(savedManager.isCredentialsSetupRequired(), "Manager must have credentialsSetupRequired = false");
    }
}
