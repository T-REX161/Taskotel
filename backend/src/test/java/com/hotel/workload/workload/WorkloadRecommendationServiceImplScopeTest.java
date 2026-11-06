package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * How {@link WorkloadRecommendationServiceImpl} uses the repositories for each scope, with every collaborator mocked: a Manager's run uses the
 * department-filtered queries and never loads the global task or assignment datasets, an Admin keeps the global queries, the scope is resolved
 * once, and a refused scope stops before any repository is read. Behaviour through the real security chain and database is in
 * {@link WorkloadRecommendationDepartmentIsolationTest}.
 */
public class WorkloadRecommendationServiceImplScopeTest {

    private DepartmentRepository departmentRepository;
    private StaffRepository staffRepository;
    private TaskRepository taskRepository;
    private AssignmentRepository assignmentRepository;
    private CurrentUserScopeResolver scopeResolver;
    private WorkloadRecommendationServiceImpl service;
    private Department alpha;

    @BeforeEach
    public void setUp() {
        departmentRepository = mock(DepartmentRepository.class);
        staffRepository = mock(StaffRepository.class);
        taskRepository = mock(TaskRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new WorkloadRecommendationServiceImpl(departmentRepository, staffRepository, taskRepository, assignmentRepository, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
    }

    @Test
    @DisplayName("A Manager's run uses the department-scoped queries and never loads the global task or assignment datasets")
    public void managerUsesDepartmentQueriesOnly() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(taskRepository.findWaitingByDepartment(eq(alpha), any())).thenReturn(List.of());
        when(taskRepository.countWaitingByDepartmentCode(any())).thenReturn(List.of());
        when(assignmentRepository.findBusyStaffIds(any())).thenReturn(List.of());

        WorkloadRecommendationsDto result = service.getRecommendations();

        assertNotNull(result);
        verify(scopeResolver, times(1)).resolve();
        verify(taskRepository).findWaitingByDepartment(eq(alpha), any());
        verify(taskRepository).countWaitingByDepartmentCode(any());
        verify(assignmentRepository).findBusyStaffIds(any());
        verify(taskRepository, never()).findByStatusIn(any());
        verify(taskRepository, never()).findAll();
        verify(assignmentRepository, never()).findAllActiveAssignments(any());
        verify(assignmentRepository, never()).findAll();
    }

    @Test
    @DisplayName("An Admin keeps the global queries and never uses the department-scoped ones")
    public void adminKeepsGlobalQueries() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());

        service.getRecommendations();

        verify(scopeResolver, times(1)).resolve();
        verify(taskRepository).findByStatusIn(any());
        verify(assignmentRepository).findAllActiveAssignments(any());
        verify(taskRepository, never()).findWaitingByDepartment(any(), any());
        verify(taskRepository, never()).countWaitingByDepartmentCode(any());
        verify(assignmentRepository, never()).findBusyStaffIds(any());
    }

    @Test
    @DisplayName("Staff scope is refused with 403 before any repository is read")
    public void staffScopeIsRefused() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.getRecommendations());

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verifyNoInteractions(departmentRepository, staffRepository, taskRepository, assignmentRepository);
    }

    @Test
    @DisplayName("A refused scope (invalid Manager or no sign-in) stops before any repository is read")
    public void refusedScopeStopsEarly() {
        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        doThrow(refusal).when(scopeResolver).resolve();

        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.getRecommendations()));

        verifyNoInteractions(departmentRepository, staffRepository, taskRepository, assignmentRepository);
    }
}
