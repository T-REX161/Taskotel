package com.hotel.workload.dashboard;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.workload.ScopedWorkloadCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * How {@link DashboardServiceImpl} uses the repositories for each scope, with every collaborator mocked: a Manager's summary is built from
 * department-filtered queries only (never from a global count), an Admin keeps the global queries, the scope is resolved once, and a refused
 * scope stops the call before any repository is read. Behaviour through the real security chain and database is in
 * {@link DashboardDepartmentIsolationTest}.
 */
public class DashboardServiceImplScopeTest {

    private StaffRepository staffRepository;
    private TaskRepository taskRepository;
    private AssignmentRepository assignmentRepository;
    private ScopedWorkloadCalculator workloadService;
    private CurrentUserScopeResolver scopeResolver;
    private DashboardServiceImpl service;
    private Department alpha;
    private UsernamePasswordAuthenticationToken token;

    @BeforeEach
    public void setUp() {
        staffRepository = mock(StaffRepository.class);
        taskRepository = mock(TaskRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        workloadService = mock(ScopedWorkloadCalculator.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new DashboardServiceImpl(staffRepository, taskRepository, assignmentRepository, workloadService, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
        // The authority says ADMIN on purpose: only the resolved scope may decide
        token = new UsernamePasswordAuthenticationToken("someone", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(workloadService.calculateWorkload(any())).thenReturn(List.of());
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A Manager's summary uses only the department queries, whatever authority string the caller presents")
    public void managerSummaryUsesDepartmentQueriesOnly() {
        DepartmentScope managerScope = DepartmentScope.department(alpha);
        when(scopeResolver.resolve()).thenReturn(managerScope);

        Object result = service.getDashboardSummary(token);

        assertInstanceOf(AdminDashboardSummaryDto.class, result);
        verify(scopeResolver, times(1)).resolve();
        verify(staffRepository).countOperationalInDepartment(alpha);
        verify(staffRepository).countOperationalInactiveInDepartment(alpha);
        verify(staffRepository).countOperationalActiveByAvailabilityInDepartment(alpha, Availability.AVAILABLE);
        verify(staffRepository).countOperationalActiveByAvailabilityInDepartment(alpha, Availability.UNAVAILABLE);
        verify(taskRepository).countByDepartment(alpha);
        verify(taskRepository, times(5)).countByDepartmentAndStatus(eq(alpha), any(TaskStatus.class));
        verify(assignmentRepository).countActiveByTaskAndStaffDepartment(any(), eq(alpha));
        verify(workloadService, times(1)).calculateWorkload(same(managerScope));

        verify(staffRepository, never()).countOperational();
        verify(staffRepository, never()).countOperationalInactive();
        verify(staffRepository, never()).countOperationalActiveByAvailability(any());
        verify(taskRepository, never()).count();
        verify(taskRepository, never()).countByStatus(any());
        verify(assignmentRepository, never()).countActiveAssignments(any());
        verify(staffRepository, never()).findAll();
    }

    @Test
    @DisplayName("An Admin keeps the global queries and never uses the department ones")
    public void adminKeepsGlobalQueries() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());

        Object result = service.getDashboardSummary(token);

        assertInstanceOf(AdminDashboardSummaryDto.class, result);
        verify(scopeResolver, times(1)).resolve();
        verify(workloadService, times(1)).calculateWorkload(same(DepartmentScope.all()));
        verify(staffRepository).countOperational();
        verify(taskRepository).count();
        verify(assignmentRepository).countActiveAssignments(any());
        verify(staffRepository, never()).countOperationalInDepartment(any());
        verify(taskRepository, never()).countByDepartment(any());
        verify(taskRepository, never()).countByDepartmentAndStatus(any(), any());
        verify(assignmentRepository, never()).countActiveByTaskAndStaffDepartment(any(), any());
    }

    @Test
    @DisplayName("A refused scope (invalid Manager or no sign-in) stops before any repository or the workload is read")
    public void refusedScopeStopsEarly() {
        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        doThrow(refusal).when(scopeResolver).resolve();

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.getDashboardSummary(token));

        assertSame(refusal, thrown);
        verifyNoInteractions(staffRepository, taskRepository, assignmentRepository, workloadService);
    }

    @Test
    @DisplayName("Staff get their own summary only: no aggregate query is used")
    public void staffUsesOwnDataOnly() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        // No Staff record: refused before any aggregate is read
        when(staffRepository.findByUserUsername("someone")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.getDashboardSummary(token));

        verify(staffRepository).findByUserUsername("someone");
        verify(staffRepository, never()).countOperational();
        verify(taskRepository, never()).count();
        verify(assignmentRepository, never()).countActiveAssignments(any());
        verify(staffRepository, never()).countOperationalInDepartment(any());
    }

    @Test
    @DisplayName("Staff: the own-row workload is calculated under the very scope the dashboard resolved, and the scope is resolved once")
    public void staffWorkloadUsesTheResolvedScope() {
        DepartmentScope own = DepartmentScope.own();
        when(scopeResolver.resolve()).thenReturn(own);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        com.hotel.workload.staff.Staff staff = new com.hotel.workload.staff.Staff();
        staff.setId(java.util.UUID.randomUUID());
        when(staffRepository.findByUserUsername("someone")).thenReturn(java.util.Optional.of(staff));
        when(assignmentRepository.findByStaffId(staff.getId())).thenReturn(List.of());

        service.getDashboardSummary(token);

        verify(scopeResolver, times(1)).resolve();
        verify(workloadService, times(1)).calculateWorkload(same(own));
    }
}
