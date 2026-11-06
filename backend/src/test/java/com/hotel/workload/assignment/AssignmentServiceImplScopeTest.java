package com.hotel.workload.assignment;

import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportLifecycle;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * How {@link AssignmentServiceImpl} uses the repositories for each scope, with every collaborator mocked: a Manager's list is a department
 * query (never all assignments), a Manager's create and reassign take only department-scoped locks (so an out-of-scope task or staff member
 * is never locked, and the staff lock is never taken once the task is refused), an Admin keeps the existing locks, and a refused scope stops
 * the call before any repository is read. The behaviour through the real security chain and database is in
 * {@link AssignmentDepartmentIsolationTest}.
 */
public class AssignmentServiceImplScopeTest {

    private AssignmentRepository assignmentRepository;
    private TaskRepository taskRepository;
    private StaffRepository staffRepository;
    private SupportLifecycle supportLifecycle;
    private CurrentUserScopeResolver scopeResolver;
    private AssignmentServiceImpl service;
    private Department alpha;
    private Department beta;

    @BeforeEach
    public void setUp() {
        assignmentRepository = mock(AssignmentRepository.class);
        taskRepository = mock(TaskRepository.class);
        staffRepository = mock(StaffRepository.class);
        supportLifecycle = mock(SupportLifecycle.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new AssignmentServiceImpl(assignmentRepository, taskRepository, staffRepository, supportLifecycle, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
        beta = new Department("BETA", "Beta");
    }

    private Task taskIn(Department department) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setTitle("t");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setDepartment(department);
        return task;
    }

    private Staff staffIn(Department department) {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setName("s");
        staff.setDepartment(department);
        staff.setActive(true);
        staff.setAvailability(Availability.AVAILABLE);
        return staff;
    }

    private HttpStatus statusOf(Runnable call) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
    }

    @Test
    @DisplayName("A Manager's list is a department query: the all-assignments query is never used")
    public void managerListUsesTheDepartmentQuery() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(assignmentRepository.findByTaskDepartmentOrderByAssignedAtDesc(alpha)).thenReturn(List.of());

        assertTrue(service.getAllAssignments().isEmpty());

        verify(assignmentRepository).findByTaskDepartmentOrderByAssignedAtDesc(alpha);
        verify(assignmentRepository, never()).findAllByOrderByAssignedAtDesc();
        verify(assignmentRepository, never()).findByStaffIdOrderByAssignedAtDesc(any());
    }

    @Test
    @DisplayName("An Admin's list is the global query")
    public void adminListIsGlobal() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(assignmentRepository.findAllByOrderByAssignedAtDesc()).thenReturn(List.of());

        service.getAllAssignments();

        verify(assignmentRepository).findAllByOrderByAssignedAtDesc();
        verify(assignmentRepository, never()).findByTaskDepartmentOrderByAssignedAtDesc(any());
    }

    @Test
    @DisplayName("Staff keep the own-assignments query and never reach the department or global queries")
    public void staffListIsUnchanged() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("sam", null, List.of()));
        try {
            when(staffRepository.findByUserUsername("sam")).thenReturn(Optional.empty());

            assertTrue(service.getAllAssignments().isEmpty());

            verify(staffRepository).findByUserUsername("sam");
            verify(assignmentRepository, never()).findAllByOrderByAssignedAtDesc();
            verify(assignmentRepository, never()).findByTaskDepartmentOrderByAssignedAtDesc(any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("A Manager's create: an out-of-scope task is never locked and the staff member is never locked or read")
    public void managerCreateWithOutOfScopeTaskLocksNothing() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        UUID taskId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        when(taskRepository.lockByIdAndDepartment(taskId, alpha)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.createAssignment(new CreateAssignmentRequest(taskId, staffId))));

        verify(taskRepository).lockByIdAndDepartment(taskId, alpha);
        verify(taskRepository, never()).lockByIds(any());
        verify(taskRepository, never()).findById(any());
        verifyNoInteractions(staffRepository);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("A Manager's create: an out-of-scope staff member is never locked through the unscoped lock, and nothing is saved")
    public void managerCreateWithOutOfScopeStaffIsRefused() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        Task task = taskIn(alpha);
        UUID staffId = UUID.randomUUID();
        when(taskRepository.lockByIdAndDepartment(task.getId(), alpha)).thenReturn(Optional.of(task));
        when(staffRepository.lockByIdAndDepartment(staffId, alpha)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.createAssignment(new CreateAssignmentRequest(task.getId(), staffId))));

        InOrder order = inOrder(taskRepository, staffRepository);
        order.verify(taskRepository).lockByIdAndDepartment(task.getId(), alpha);
        order.verify(staffRepository).lockByIdAndDepartment(staffId, alpha);
        verify(staffRepository, never()).lockByIds(any());
        verify(staffRepository, never()).findById(any());
        verify(assignmentRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("A Manager's reassign: an out-of-scope or missing assignment locks no task and no staff member (same 404)")
    public void managerReassignOutOfScopeLocksNothing() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        UUID missing = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        UUID outsideTask = UUID.randomUUID();
        when(assignmentRepository.findTaskIdById(missing)).thenReturn(Optional.empty());
        when(assignmentRepository.findTaskIdById(outside)).thenReturn(Optional.of(outsideTask));
        when(taskRepository.lockByIdAndDepartment(outsideTask, alpha)).thenReturn(Optional.empty());
        UpdateAssignmentRequest request = new UpdateAssignmentRequest(UUID.randomUUID());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.reassignTask(missing, request)));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.reassignTask(outside, request)));

        verify(taskRepository, never()).lockByIds(any());
        verifyNoInteractions(staffRepository);
        verify(assignmentRepository, never()).findById(any());
        verify(assignmentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(supportLifecycle);
    }

    @Test
    @DisplayName("A Manager's reassign takes the task lock first and the target staff lock second, both department-scoped")
    public void managerReassignKeepsTheLockOrder() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        Task task = taskIn(alpha);
        Staff holder = staffIn(beta); // lent from another department: must not matter
        Staff target = staffIn(alpha);
        Assignment assignment = new Assignment();
        assignment.setId(UUID.randomUUID());
        assignment.setTask(task);
        assignment.setStaff(holder);
        when(assignmentRepository.findTaskIdById(assignment.getId())).thenReturn(Optional.of(task.getId()));
        when(taskRepository.lockByIdAndDepartment(task.getId(), alpha)).thenReturn(Optional.of(task));
        when(staffRepository.lockByIdAndDepartment(target.getId(), alpha)).thenReturn(Optional.of(target));
        when(assignmentRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        when(assignmentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.reassignTask(assignment.getId(), new UpdateAssignmentRequest(target.getId()));

        InOrder order = inOrder(taskRepository, staffRepository, assignmentRepository);
        order.verify(taskRepository).lockByIdAndDepartment(task.getId(), alpha);
        order.verify(staffRepository).lockByIdAndDepartment(target.getId(), alpha);
        order.verify(assignmentRepository).findById(assignment.getId());
        assertSame(target, assignment.getStaff());
        verify(supportLifecycle).onTaskReassigned(task.getId(), target.getId());
        verify(taskRepository, never()).lockByIds(any());
        verify(staffRepository, never()).lockByIds(any());
    }

    @Test
    @DisplayName("An Admin keeps the existing task-then-staff locks and is never limited to a department")
    public void adminUsesTheExistingLocks() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        Task task = taskIn(null);
        Staff staff = staffIn(alpha);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.createAssignment(new CreateAssignmentRequest(task.getId(), staff.getId()));

        InOrder order = inOrder(taskRepository, staffRepository);
        order.verify(taskRepository).lockByIds(Set.of(task.getId()));
        order.verify(staffRepository).lockByIds(Set.of(staff.getId()));
        verify(taskRepository, never()).lockByIdAndDepartment(any(), any());
        verify(staffRepository, never()).lockByIdAndDepartment(any(), any());
    }

    @Test
    @DisplayName("A Staff scope and a resolver refusal stop every operation before any repository is read")
    public void refusedScopesStopBeforeAnyRepositoryIsRead() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        UUID id = UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.createAssignment(new CreateAssignmentRequest(id, id))));
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.reassignTask(id, new UpdateAssignmentRequest(id))));

        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in");
        when(scopeResolver.resolve()).thenThrow(refusal);
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.getAllAssignments()));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.createAssignment(new CreateAssignmentRequest(id, id))));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.reassignTask(id, new UpdateAssignmentRequest(id))));

        verifyNoInteractions(assignmentRepository, taskRepository, staffRepository, supportLifecycle);
    }
}
