package com.hotel.workload.security;

import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StaffRepository staffRepository;

    private UserService userService;

    @BeforeEach
    public void setUp() {
        userService = new UserServiceImpl(userRepository, staffRepository);
    }

    @Test
    public void testGetAllUsers_EmptyList() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        List<UserDto> result = userService.getAllUsers();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(userRepository, times(1)).findAll();
        verify(staffRepository, never()).findAll();
    }

    @Test
    public void testGetAllUsers_ResolvesStaffFullName() {
        UUID staffUserId = UUID.randomUUID();
        User staffUser = new User();
        staffUser.setId(staffUserId);
        staffUser.setUsername("maria_s");
        staffUser.setEmail("maria@hotel.com");
        staffUser.setRole(Role.STAFF);
        staffUser.setActive(true);
        staffUser.setEmailVerified(true);
        staffUser.setCreatedAt(LocalDateTime.now().minusDays(5));

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(staffUser);
        staff.setName("Maria Santos");

        when(userRepository.findAll()).thenReturn(List.of(staffUser));
        when(staffRepository.findAll()).thenReturn(List.of(staff));

        List<UserDto> result = userService.getAllUsers();

        assertEquals(1, result.size());
        UserDto dto = result.get(0);
        assertEquals(staffUserId, dto.getId());
        assertEquals("maria_s", dto.getUsername());
        assertEquals("maria@hotel.com", dto.getEmail());
        assertEquals(Role.STAFF, dto.getRole());
        assertTrue(dto.isActive());
        assertTrue(dto.isEmailVerified());
        assertEquals("Maria Santos", dto.getFullName());
        assertEquals(staffUser.getCreatedAt(), dto.getCreatedAt());

        verify(userRepository, times(1)).findAll();
        verify(staffRepository, times(1)).findAll();
    }

    @Test
    public void testGetAllUsers_FallbackNamesForAdminAndManager() {
        UUID adminId = UUID.randomUUID();
        User adminUser = new User();
        adminUser.setId(adminId);
        adminUser.setUsername("admin");
        adminUser.setEmail("admin@hotel.com");
        adminUser.setRole(Role.ADMIN);
        adminUser.setActive(true);
        adminUser.setEmailVerified(true);
        adminUser.setCreatedAt(LocalDateTime.now().minusDays(10));

        UUID managerId = UUID.randomUUID();
        User managerUser = new User();
        managerUser.setId(managerId);
        managerUser.setUsername("sarah_jenkins");
        managerUser.setEmail("sarah@hotel.com");
        managerUser.setRole(Role.MANAGER);
        managerUser.setActive(true);
        managerUser.setEmailVerified(true);
        managerUser.setCreatedAt(LocalDateTime.now().minusDays(8));

        when(userRepository.findAll()).thenReturn(List.of(adminUser, managerUser));
        when(staffRepository.findAll()).thenReturn(Collections.emptyList());

        List<UserDto> result = userService.getAllUsers();

        assertEquals(2, result.size());

        UserDto adminDto = result.get(0);
        assertEquals(adminId, adminDto.getId());
        assertEquals("admin", adminDto.getUsername());
        assertEquals(Role.ADMIN, adminDto.getRole());
        assertEquals("Admin", adminDto.getFullName());

        UserDto managerDto = result.get(1);
        assertEquals(managerId, managerDto.getId());
        assertEquals("sarah_jenkins", managerDto.getUsername());
        assertEquals(Role.MANAGER, managerDto.getRole());
        assertEquals("Sarah Jenkins", managerDto.getFullName());
    }

    @Test
    public void testDeriveFallbackName_FormattingVariations() {
        User u1 = new User();
        u1.setUsername("john.doe");
        assertEquals("John Doe", UserDto.deriveFallbackName(u1));

        User u2 = new User();
        u2.setUsername("carlos-rodriguez");
        assertEquals("Carlos Rodriguez", UserDto.deriveFallbackName(u2));

        User u3 = new User();
        u3.setUsername("priya");
        assertEquals("Priya", UserDto.deriveFallbackName(u3));

        // When username is blank, fallback to email prefix
        User u4 = new User();
        u4.setUsername("");
        u4.setEmail("supervisor_desk@hotel.com");
        assertEquals("Supervisor Desk", UserDto.deriveFallbackName(u4));

        // When username and email are blank, fallback to role
        User u5 = new User();
        u5.setRole(Role.ADMIN);
        assertEquals("Administrator", UserDto.deriveFallbackName(u5));

        User u6 = new User();
        u6.setRole(Role.MANAGER);
        assertEquals("Manager", UserDto.deriveFallbackName(u6));

        User u7 = new User();
        u7.setRole(Role.STAFF);
        assertEquals("Staff", UserDto.deriveFallbackName(u7));

        // Null user
        assertEquals("Unknown User", UserDto.deriveFallbackName(null));
    }

    @Test
    public void testSensitiveFieldsNeverExposedInDto() {
        // Reflection check: UserDto must not contain any sensitive fields
        List<String> forbiddenFieldNames = List.of(
                "password",
                "passwordResetCodeHash",
                "passwordResetExpiresAt",
                "passwordResetAttemptCount",
                "passwordResetLastSentAt",
                "verificationCodeHash",
                "verificationCodeExpiresAt",
                "verificationAttemptCount",
                "setupToken"
        );

        Field[] fields = UserDto.class.getDeclaredFields();
        for (Field field : fields) {
            assertFalse(forbiddenFieldNames.contains(field.getName()),
                    "UserDto must not declare sensitive field: " + field.getName());
        }

        Method[] methods = UserDto.class.getDeclaredMethods();
        for (Method method : methods) {
            String methodName = method.getName().toLowerCase();
            for (String forbidden : forbiddenFieldNames) {
                assertFalse(methodName.contains(forbidden.toLowerCase()),
                        "UserDto must not declare method exposing: " + forbidden);
            }
        }

        // Entity mapping check: populating secrets on User does not expose them
        User secretUser = new User();
        secretUser.setId(UUID.randomUUID());
        secretUser.setUsername("testuser");
        secretUser.setEmail("test@hotel.com");
        secretUser.setPassword("superSecretHashedPassword");
        secretUser.setRole(Role.STAFF);
        secretUser.setPasswordResetCodeHash("resetCodeHash123");
        secretUser.setVerificationCodeHash("verificationCodeHash456");
        secretUser.setSetupToken("setupToken789");

        UserDto dto = UserDto.fromEntity(secretUser, "Test User");

        assertNotNull(dto);
        assertEquals("testuser", dto.getUsername());
        assertEquals("test@hotel.com", dto.getEmail());
        assertEquals("Test User", dto.getFullName());
    }

    @Test
    public void testGetAllUsers_IgnoresStaffWithNullUserOrBlankName() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setUsername("alex_turner");
        user.setRole(Role.STAFF);

        Staff staffWithoutUser = new Staff();
        staffWithoutUser.setUser(null);
        staffWithoutUser.setName("Ghost Staff");

        Staff staffWithBlankName = new Staff();
        staffWithBlankName.setUser(user);
        staffWithBlankName.setName("   ");

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(staffRepository.findAll()).thenReturn(List.of(staffWithoutUser, staffWithBlankName));

        List<UserDto> result = userService.getAllUsers();

        assertEquals(1, result.size());
        assertEquals("Alex Turner", result.get(0).getFullName());
    }
}
