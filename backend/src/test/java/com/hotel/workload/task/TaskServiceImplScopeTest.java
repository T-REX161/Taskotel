package com.hotel.workload.task;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentResolver;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * How {@link TaskServiceImpl} uses the repositories for each scope, with every collaborator mocked: the Manager's list is a department
 * query (never all tasks), a Manager's single-task operations take only the department-scoped lock, an Admin keeps the existing lock, and a
 * refused scope stops the call before any repository is read. The behaviour through the real security chain and database is in
 * {@link TaskDepartmentIsolationTest}.
 */
public class TaskServiceImplScopeTest {

    private TaskRepository taskRepository;
    private StaffRepository staffRepository;
    private AssignmentRepository assignmentRepository;
    private SupportLifecycle supportLifecycle;
    private CurrentUserScopeResolver scopeResolver;
    private TaskServiceImpl service;
    private Department alpha;

    @BeforeEach
    public void setUp() {
        taskRepository = mock(TaskRepository.class);
        staffRepository = mock(StaffRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        supportLifecycle = mock(SupportLifecycle.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new TaskServiceImpl(taskRepository, mock(UserRepository.class), staffRepository, assignmentRepository,
                mock(DepartmentResolver.class), supportLifecycle, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
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

    private UpdateTaskRequest update(String department) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle("t");
        request.setPriority(Priority.MEDIUM);
        request.setStatus(TaskStatus.PENDING);
        request.setDepartment(department);
        return request;
    }

    @Test
    @DisplayName("A Manager's list is a department query: findAll is never called")
    public void managerListUsesTheDepartmentQuery() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(taskRepository.findByDepartment(alpha)).thenReturn(List.of(taskIn(alpha)));

        assertEquals(1, service.getAllTasks().size());

        verify(taskRepository).findByDepartment(alpha);
        verify(taskRepository, never()).findAll();
        verify(taskRepository, never()).findTasksByStaffId(any());
    }

    @Test
    @DisplayName("An Admin's list loads every task")
    public void adminListLoadsEverything() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(taskRepository.findAll()).thenReturn(List.of(taskIn(alpha), taskIn(null)));

        assertEquals(2, service.getAllTasks().size());

        verify(taskRepository, never()).findByDepartment(any());
    }

    @Test
    @DisplayName("Staff keep the assigned-task query and never reach the department or all-tasks queries")
    public void staffListIsUnchanged() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("sam", null, List.of()));
        try {
            when(staffRepository.findByUserUsername("sam")).thenReturn(Optional.empty());

            assertTrue(service.getAllTasks().isEmpty());

            verify(staffRepository).findByUserUsername("sam");
            verify(taskRepository, never()).findAll();
            verify(taskRepository, never()).findByDepartment(any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("A Manager's update and delete take only the department-scoped lock; a task outside the department is never locked, and is 404")
    public void managerSingleTaskOperationsUseTheScopedLock() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        UUID otherId = UUID.randomUUID();
        when(taskRepository.lockByIdAndDepartment(otherId, alpha)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.updateTask(otherId, update("ALPHA"))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.deleteTask(otherId)).getStatusCode());

        verify(taskRepository, times(2)).lockByIdAndDepartment(otherId, alpha);
        verify(taskRepository, never()).lockByIds(any());
        verify(taskRepository, never()).findById(any());
        verify(taskRepository, never()).delete(any());
        verify(taskRepository, never()).saveAndFlush(any());
        verifyNoInteractions(supportLifecycle);
    }

    @Test
    @DisplayName("A Manager's request to move or clear the department is refused (403) after the scoped lock and before anything is saved")
    public void managerCannotMoveOrClearTheDepartment() {
        Task task = taskIn(alpha);
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(taskRepository.lockByIdAndDepartment(task.getId(), alpha)).thenReturn(Optional.of(task));

        for (String requested : new String[] {"BETA", "", null, "NO_SUCH"}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.updateTask(task.getId(), update(requested)));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode(), String.valueOf(requested));
        }
        verify(taskRepository, never()).saveAndFlush(any());
        verifyNoInteractions(supportLifecycle);
        assertEquals("ALPHA", task.getDepartment().getCode());
    }

    @Test
    @DisplayName("An Admin keeps the existing lock and is never limited to a department")
    public void adminUsesTheExistingLock() {
        Task task = taskIn(null);
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        service.deleteTask(task.getId());

        verify(taskRepository).lockByIds(Set.of(task.getId()));
        verify(taskRepository, never()).lockByIdAndDepartment(any(), any());
        verify(taskRepository).delete(task);
        verify(supportLifecycle).onTaskDeleted(task.getId());
    }

    @Test
    @DisplayName("A Staff scope and a resolver refusal stop every operation before any repository is read")
    public void refusedScopesStopBeforeAnyRepositoryIsRead() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        UUID id = UUID.randomUUID();
        for (Runnable call : List.<Runnable>of(
                () -> service.createTask(new CreateTaskRequest()),
                () -> service.updateTask(id, update("ALPHA")),
                () -> service.deleteTask(id))) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, call::run).getStatusCode());
        }

        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in");
        when(scopeResolver.resolve()).thenThrow(refusal);
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.getAllTasks()));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.createTask(new CreateTaskRequest())));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.updateTask(id, update("ALPHA"))));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.deleteTask(id)));

        verifyNoInteractions(taskRepository, staffRepository, assignmentRepository, supportLifecycle);
    }
}
