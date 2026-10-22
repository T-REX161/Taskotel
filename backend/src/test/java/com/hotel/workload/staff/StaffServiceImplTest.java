package com.hotel.workload.staff;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.InvalidPasswordException;
import com.hotel.workload.security.PasswordValidatorService;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class StaffServiceImplTest {

    private StaffRepository staffRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordValidatorService passwordValidatorService;
    private AssignmentRepository assignmentRepository;
    private TaskRepository taskRepository;
    private StaffServiceImpl staffService;

    @BeforeEach
    public void setUp() {
        staffRepository = mock(StaffRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        passwordValidatorService = new PasswordValidatorService();
        assignmentRepository = mock(AssignmentRepository.class);
        taskRepository = mock(TaskRepository.class);
        staffService = new StaffServiceImpl(staffRepository, userRepository, passwordEncoder, passwordValidatorService, assignmentRepository, taskRepository);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateStaffRequest createValidRequest() {
        CreateStaffRequest request = new CreateStaffRequest();
        request.setFullName("Test Staff");
        request.setEmail("test@hotel.com");
        request.setPhone("1234567890");
        request.setDepartment(Department.FRONT_DESK);
        request.setAvailability(Availability.AVAILABLE);
        request.setUsername("teststaff");
        request.setTemporaryPassword("ValidTemp@123");
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",          // < 8 characters
            "nouppercase@1",  // missing uppercase
            "NOLOWERCASE@1",  // missing lowercase
            "NoDigitsHere!",  // missing digit
            "NoSpecialChar1", // missing special char
            "Has Space@123"   // whitespace
    })
    @DisplayName("createStaff rejects weak temporary passwords with InvalidPasswordException")
    public void testCreateStaff_WeakPassword_ThrowsException(String weakPassword) {
        CreateStaffRequest request = createValidRequest();
        request.setTemporaryPassword(weakPassword);

        assertThrows(InvalidPasswordException.class, () -> staffService.createStaff(request));
        verify(userRepository, never()).save(any());
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("createStaff succeeds when temporary password satisfies security rules")
    public void testCreateStaff_ValidPassword_Success() {
        CreateStaffRequest request = createValidRequest();

        when(userRepository.existsByUsername("teststaff")).thenReturn(false);
        when(passwordEncoder.encode(request.getTemporaryPassword())).thenReturn("hashed-pwd");

        User savedUser = new User();
        savedUser.setId(UUID.randomUUID());
        savedUser.setUsername(request.getUsername());
        savedUser.setRole(Role.STAFF);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        Staff savedStaff = new Staff();
        savedStaff.setId(UUID.randomUUID());
        savedStaff.setName(request.getFullName());
        savedStaff.setEmail(request.getEmail());
        savedStaff.setDepartment(request.getDepartment());
        savedStaff.setAvailability(request.getAvailability());
        savedStaff.setActive(true);
        savedStaff.setUser(savedUser);
        when(staffRepository.save(any(Staff.class))).thenReturn(savedStaff);

        StaffDto result = staffService.createStaff(request);

        assertNotNull(result);
        assertEquals(request.getUsername(), result.getUsername());
        verify(userRepository, times(1)).save(any(User.class));
        verify(staffRepository, times(1)).save(any(Staff.class));
        verify(passwordEncoder, times(1)).encode("ValidTemp@123");
    }

    private void setSecurityContextRole(String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "testadmin",
                null,
                Collections.singletonList(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("removeStaff throws FORBIDDEN when user does not have ADMIN role")
    public void testRemoveStaff_NotAdmin_ThrowsForbidden() {
        setSecurityContextRole("ROLE_MANAGER");
        UUID staffId = UUID.randomUUID();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(staffId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("removeStaff throws BAD_REQUEST when staff member is active")
    public void testRemoveStaff_ActiveStaff_ThrowsBadRequest() {
        setSecurityContextRole("ROLE_ADMIN");
        UUID staffId = UUID.randomUUID();

        Staff activeStaff = new Staff();
        activeStaff.setId(staffId);
        activeStaff.setActive(true);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(activeStaff));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(staffId));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("removeStaff throws CONFLICT when inactive staff has active assignments")
    public void testRemoveStaff_WithActiveAssignments_ThrowsConflict() {
        setSecurityContextRole("ROLE_ADMIN");
        UUID staffId = UUID.randomUUID();

        Staff inactiveStaff = new Staff();
        inactiveStaff.setId(staffId);
        inactiveStaff.setActive(false);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(inactiveStaff));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(eq(staffId), anyList())).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(staffId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("active task assignments"));
        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("removeStaff throws CONFLICT when inactive staff has historical assignments")
    public void testRemoveStaff_WithHistoricalAssignments_ThrowsConflict() {
        setSecurityContextRole("ROLE_ADMIN");
        UUID staffId = UUID.randomUUID();

        Staff inactiveStaff = new Staff();
        inactiveStaff.setId(staffId);
        inactiveStaff.setActive(false);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(inactiveStaff));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(eq(staffId), anyList())).thenReturn(false);
        when(assignmentRepository.findByStaffId(staffId)).thenReturn(List.of(new Assignment()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.removeStaff(staffId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("historical"));
        verify(staffRepository, never()).delete(any());
    }

    @Test
    @DisplayName("removeStaff succeeds when user is ADMIN, staff is inactive, and has no assignments")
    public void testRemoveStaff_InactiveStaffWithoutAssignments_Success() {
        setSecurityContextRole("ROLE_ADMIN");
        UUID staffId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);

        Staff inactiveStaff = new Staff();
        inactiveStaff.setId(staffId);
        inactiveStaff.setActive(false);
        inactiveStaff.setUser(user);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(inactiveStaff));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(eq(staffId), anyList())).thenReturn(false);
        when(assignmentRepository.findByStaffId(staffId)).thenReturn(Collections.emptyList());
        when(taskRepository.existsByCreatedById(userId)).thenReturn(false);

        staffService.removeStaff(staffId);

        verify(staffRepository, times(1)).delete(inactiveStaff);
        verify(userRepository, times(1)).delete(user);
    }

    @Test
    @DisplayName("deactivateStaff sets staff and user active to false and preserves availability")
    public void testDeactivateStaff_Success() {
        UUID staffId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setName("Active Staff");
        staff.setActive(true);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setUser(user);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffDto result = staffService.deactivateStaff(staffId);

        assertNotNull(result);
        assertFalse(result.isActive());
        assertFalse(staff.isActive());
        assertFalse(user.isActive());
        assertEquals(Availability.AVAILABLE, staff.getAvailability());
        verify(staffRepository, times(1)).save(staff);
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("deactivateStaff is idempotent when staff and user are already inactive")
    public void testDeactivateStaff_AlreadyInactive_Idempotent() {
        UUID staffId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(false);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setActive(false);
        staff.setUser(user);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        StaffDto result = staffService.deactivateStaff(staffId);

        assertNotNull(result);
        assertFalse(result.isActive());
        verify(staffRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    private Staff activeStaffWithUser(UUID staffId) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setName("Busy Staff");
        staff.setEmail("busy@hotel.com");
        staff.setDepartment(Department.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        staff.setUser(user);
        return staff;
    }

    private UpdateStaffRequest updateRequestFor(Staff staff, boolean active) {
        UpdateStaffRequest request = new UpdateStaffRequest();
        request.setFullName(staff.getName());
        request.setEmail(staff.getEmail());
        request.setDepartment(staff.getDepartment());
        request.setAvailability(Availability.AVAILABLE);
        request.setActive(active);
        return request;
    }

    @Test
    @DisplayName("deactivateStaff throws CONFLICT and saves nothing when staff has active assignments")
    public void testDeactivateStaff_WithActiveAssignments_ThrowsConflictAndDoesNotSave() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(staffId,
                List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS))).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.deactivateStaff(staffId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("active task assignments"));
        assertTrue(staff.isActive());
        assertTrue(staff.getUser().isActive());
        verify(staffRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStaff throws CONFLICT and saves nothing when deactivating staff with active assignments")
    public void testUpdateStaff_DeactivatingWithActiveAssignments_ThrowsConflictAndDoesNotSave() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(staffId,
                List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS))).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> staffService.updateStaff(staffId, updateRequestFor(staff, false)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("active task assignments"));
        assertTrue(staff.isActive());
        assertTrue(staff.getUser().isActive());
        verify(staffRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStaff does not check assignments when the active flag is unchanged")
    public void testUpdateStaff_KeepingActive_DoesNotCheckAssignments() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffDto result = staffService.updateStaff(staffId, updateRequestFor(staff, true));

        assertTrue(result.isActive());
        verify(assignmentRepository, never()).existsByStaffIdAndTaskStatusIn(any(), anyList());
    }

    @Test
    @DisplayName("deactivateStaff succeeds when staff has no active assignments")
    public void testDeactivateStaff_WithoutActiveAssignments_Succeeds() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.existsByStaffIdAndTaskStatusIn(eq(staffId), anyList())).thenReturn(false);

        StaffDto result = staffService.deactivateStaff(staffId);

        assertFalse(result.isActive());
        assertFalse(staff.getUser().isActive());
        verify(assignmentRepository).existsByStaffIdAndTaskStatusIn(staffId,
                List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
    }

    private Assignment assignmentWithTask(String title, Department department, TaskStatus status) {
        com.hotel.workload.task.Task task = new com.hotel.workload.task.Task();
        task.setTitle(title);
        task.setDepartment(department);
        task.setStatus(status);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        return assignment;
    }

    private UpdateStaffRequest departmentChangeRequest(Staff staff, Department department) {
        UpdateStaffRequest request = updateRequestFor(staff, true);
        request.setEmail("changed@hotel.com");
        request.setDepartment(department);
        return request;
    }

    @Test
    @DisplayName("updateStaff throws CONFLICT and saves nothing when a department change conflicts with active tasks")
    public void testUpdateStaff_DepartmentChangeWithConflictingActiveTask_ThrowsConflictAndDoesNotSave() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId); // HOUSEKEEPING
        staff.getUser().setEmail("busy@hotel.com");
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staffId))
                .thenReturn(List.of(assignmentWithTask("Room 12 cleaning", Department.HOUSEKEEPING, TaskStatus.IN_PROGRESS)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> staffService.updateStaff(staffId, departmentChangeRequest(staff, Department.SECURITY)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Cannot move Busy Staff to Security because they have active tasks in another department: "
                + "Room 12 cleaning (Housekeeping). Reassign or complete those tasks first.", ex.getReason());
        assertEquals(Department.HOUSEKEEPING, staff.getDepartment());
        assertEquals("busy@hotel.com", staff.getEmail());
        assertEquals("busy@hotel.com", staff.getUser().getEmail());
        verify(staffRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStaff allows a department change when only completed, cancelled or same-department tasks exist")
    public void testUpdateStaff_DepartmentChangeWithoutConflictingActiveTasks_Succeeds() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId); // HOUSEKEEPING
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.findByStaffIdOrderByAssignedAtDesc(staffId)).thenReturn(List.of(
                assignmentWithTask("Done task", Department.HOUSEKEEPING, TaskStatus.COMPLETED),
                assignmentWithTask("Cancelled task", Department.HOUSEKEEPING, TaskStatus.CANCELLED),
                assignmentWithTask("Security patrol", Department.SECURITY, TaskStatus.ASSIGNED),
                assignmentWithTask("Unscoped task", null, TaskStatus.PENDING)));

        StaffDto result = staffService.updateStaff(staffId, departmentChangeRequest(staff, Department.SECURITY));

        assertEquals(Department.SECURITY, result.getDepartment());
        verify(staffRepository).save(staff);
    }

    @Test
    @DisplayName("updateStaff does not load assignments when the department is unchanged")
    public void testUpdateStaff_DepartmentUnchanged_DoesNotLoadAssignments() {
        UUID staffId = UUID.randomUUID();
        Staff staff = activeStaffWithUser(staffId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        staffService.updateStaff(staffId, updateRequestFor(staff, true));

        verify(assignmentRepository, never()).findByStaffIdOrderByAssignedAtDesc(any());
    }

    @Test
    @DisplayName("deactivateStaff throws NOT_FOUND when staff does not exist")
    public void testDeactivateStaff_NotFound() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.deactivateStaff(staffId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("activateStaff sets staff and user active to true and preserves availability")
    public void testActivateStaff_Success() {
        UUID staffId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(false);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setName("Inactive Staff");
        staff.setActive(false);
        staff.setAvailability(Availability.UNAVAILABLE);
        staff.setUser(user);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffDto result = staffService.activateStaff(staffId);

        assertNotNull(result);
        assertTrue(result.isActive());
        assertTrue(staff.isActive());
        assertTrue(user.isActive());
        // Verify availability was NOT silently set to AVAILABLE; it remains UNAVAILABLE
        assertEquals(Availability.UNAVAILABLE, staff.getAvailability());
        verify(staffRepository, times(1)).save(staff);
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("activateStaff is idempotent when staff and user are already active")
    public void testActivateStaff_AlreadyActive_Idempotent() {
        UUID staffId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setActive(true);
        staff.setUser(user);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        StaffDto result = staffService.activateStaff(staffId);

        assertNotNull(result);
        assertTrue(result.isActive());
        verify(staffRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("activateStaff throws NOT_FOUND when staff does not exist")
    public void testActivateStaff_NotFound() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> staffService.activateStaff(staffId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
