package com.hotel.workload.workload;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.Role;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * How {@link WorkloadServiceImpl} uses the repositories for each scope, with every collaborator mocked: a Manager's view is built from
 * department-filtered queries only (never from a global load), an Admin keeps the global calculation, Staff keep the own-row lookup, and a
 * refused scope stops the call before any repository is read. The behaviour through the real security chain and database is in
 * {@link WorkloadDepartmentIsolationTest}.
 */
public class WorkloadServiceImplScopeTest {

    private StaffRepository staffRepository;
    private AssignmentRepository assignmentRepository;
    private SupportAssignmentRepository supportRepository;
    private DepartmentRepository departmentRepository;
    private CurrentUserScopeResolver scopeResolver;
    private WorkloadServiceImpl service;
    private Department alpha;

    @BeforeEach
    public void setUp() {
        staffRepository = mock(StaffRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        supportRepository = mock(SupportAssignmentRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new WorkloadServiceImpl(staffRepository, assignmentRepository, supportRepository, departmentRepository, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A Manager's view uses only the department queries: no global staff, assignment, support or department load")
    public void managerViewIsBuiltFromDepartmentQueriesOnly() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(staffRepository.findByDepartmentAndUserRole(alpha, Role.STAFF)).thenReturn(List.of());
        when(assignmentRepository.findActiveByTaskAndStaffDepartment(any(), eq(alpha))).thenReturn(List.of());

        assertTrue(service.calculateWorkload().isEmpty());

        verify(staffRepository).findByDepartmentAndUserRole(alpha, Role.STAFF);
        verify(assignmentRepository).findActiveByTaskAndStaffDepartment(any(), eq(alpha));
        verify(staffRepository, never()).findAll();
        verify(assignmentRepository, never()).findAllActiveAssignments(any());
        verifyNoInteractions(supportRepository, departmentRepository);
    }

    @Test
    @DisplayName("An Admin keeps the global calculation and never uses the department queries")
    public void adminKeepsTheGlobalCalculation() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(staffRepository.findAll()).thenReturn(List.of());
        when(assignmentRepository.findAllActiveAssignments(any())).thenReturn(List.of());
        when(supportRepository.findByStatus(any())).thenReturn(List.of());

        service.calculateWorkload();

        verify(staffRepository).findAll();
        verify(assignmentRepository).findAllActiveAssignments(any());
        verify(assignmentRepository, never()).findActiveByTaskAndStaffDepartment(any(), any());
        verify(staffRepository, never()).findByDepartmentAndUserRole(any(), any());
    }

    @Test
    @DisplayName("Staff keep the own-row lookup and never reach the department queries; no Staff record is still 403")
    public void staffKeepTheOwnRow() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("sam", null, List.of()));
        when(staffRepository.findByUserUsername("sam")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.FORBIDDEN, HttpStatus.valueOf(assertThrows(ResponseStatusException.class, () -> service.calculateWorkload()).getStatusCode().value()));

        verify(staffRepository).findByUserUsername("sam");
        verify(staffRepository, never()).findAll();
        verify(staffRepository, never()).findByDepartmentAndUserRole(any(), any());
        verify(assignmentRepository, never()).findActiveByTaskAndStaffDepartment(any(), any());
    }

    @Test
    @DisplayName("A resolver refusal (invalid Manager 403, no sign-in 401) stops the call before any repository is read")
    public void refusalsStopBeforeAnyRepositoryIsRead() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED}) {
            ResponseStatusException refusal = new ResponseStatusException(status, "refused");
            doThrow(refusal).when(scopeResolver).resolve();
            assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.calculateWorkload()));
        }
        verifyNoInteractions(staffRepository, assignmentRepository, supportRepository, departmentRepository);
    }

    @Test
    @DisplayName("The scope is resolved exactly once per request")
    public void scopeIsResolvedOnce() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(staffRepository.findByDepartmentAndUserRole(any(), any())).thenReturn(List.of());
        when(assignmentRepository.findActiveByTaskAndStaffDepartment(any(), any())).thenReturn(List.of());

        service.calculateWorkload();

        verify(scopeResolver, times(1)).resolve();
    }

    @Test
    @DisplayName("calculateWorkload() resolves the scope itself, exactly once, and delegates to the scoped calculation")
    public void publicCallResolvesItsOwnScope() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(staffRepository.findByDepartmentAndUserRole(alpha, Role.STAFF)).thenReturn(List.of());
        when(assignmentRepository.findActiveByTaskAndStaffDepartment(any(), eq(alpha))).thenReturn(List.of());

        service.calculateWorkload();

        verify(scopeResolver, times(1)).resolve();
    }

    @Test
    @DisplayName("calculateWorkload(scope) uses the scope it is given and never touches the resolver")
    public void scopedCallNeverResolves() {
        when(staffRepository.findByDepartmentAndUserRole(alpha, Role.STAFF)).thenReturn(List.of());
        when(assignmentRepository.findActiveByTaskAndStaffDepartment(any(), eq(alpha))).thenReturn(List.of());

        service.calculateWorkload(DepartmentScope.department(alpha));

        verifyNoInteractions(scopeResolver);
        verify(staffRepository).findByDepartmentAndUserRole(alpha, Role.STAFF);
        verify(staffRepository, never()).findAll();
        assertThrows(NullPointerException.class, () -> service.calculateWorkload((DepartmentScope) null));
    }
}
