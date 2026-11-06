package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.DepartmentScope;
import com.hotel.workload.security.UserRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * How the support READ paths use the repositories for each scope, with every collaborator mocked (Step 2G-2): a Manager's history comes from
 * the department-filtered queries and never from a global load, an Admin keeps the global queries, the scope is resolved once per request, and a
 * refused scope or another department's options/preview stop before any repository is read. Behaviour through the real security chain and
 * database is in {@link SupportReadDepartmentIsolationTest}.
 */
public class SupportAssignmentServiceImplScopeTest {

    private DepartmentRepository departmentRepository;
    private StaffRepository staffRepository;
    private TaskRepository taskRepository;
    private AssignmentRepository assignmentRepository;
    private SupportAssignmentRepository supportRepository;
    private UserRepository userRepository;
    private CurrentUserScopeResolver scopeResolver;
    private SupportAssignmentServiceImpl service;
    private Department alpha;

    @BeforeEach
    public void setUp() {
        departmentRepository = mock(DepartmentRepository.class);
        staffRepository = mock(StaffRepository.class);
        taskRepository = mock(TaskRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        supportRepository = mock(SupportAssignmentRepository.class);
        userRepository = mock(UserRepository.class);
        scopeResolver = mock(CurrentUserScopeResolver.class);
        service = new SupportAssignmentServiceImpl(departmentRepository, staffRepository, taskRepository, assignmentRepository,
                supportRepository, userRepository, scopeResolver);
        alpha = new Department("ALPHA", "Alpha");
    }

    private void verifyNothingRead() {
        verifyNoInteractions(departmentRepository, staffRepository, taskRepository, assignmentRepository, supportRepository, userRepository);
    }

    private SupportPlanRequest planFor(String code) {
        SupportPlanRequest request = new SupportPlanRequest();
        request.setReceivingDepartment(code);
        request.setItems(List.of(new SupportPlanRequest.Item(UUID.randomUUID(), UUID.randomUUID())));
        return request;
    }

    @Test
    @DisplayName("A Manager history uses the department-filtered query and never loads the global history")
    public void managerHistoryUsesDepartmentQuery() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(supportRepository.findHistoryInvolvingDepartment("ALPHA")).thenReturn(List.of());
        when(supportRepository.findByStatusInvolvingDepartment(SupportStatus.ACTIVE, "ALPHA")).thenReturn(List.of());

        assertTrue(service.list(null).isEmpty());

        verify(scopeResolver, times(1)).resolve();
        verify(supportRepository).findHistoryInvolvingDepartment("ALPHA");
        verify(supportRepository).findByStatusInvolvingDepartment(SupportStatus.ACTIVE, "ALPHA");
        verify(supportRepository, never()).findByStatus(any());   // the global reconcile is not run for a Manager read
        verify(supportRepository, never()).findAllByArchivedAtIsNullOrderByCreatedAtDesc();
        verify(supportRepository, never()).findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(any());
        verify(supportRepository, never()).findAll();
        verify(supportRepository, never()).findAllByOrderByCreatedAtDesc();
        verify(supportRepository, never()).findByStatusOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("A Manager history with a status filter uses the department-and-status query")
    public void managerHistoryWithStatusUsesDepartmentQuery() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(supportRepository.findHistoryInvolvingDepartmentAndStatus("ALPHA", SupportStatus.ACTIVE)).thenReturn(List.of());
        when(supportRepository.findByStatusInvolvingDepartment(SupportStatus.ACTIVE, "ALPHA")).thenReturn(List.of());

        service.list(SupportStatus.ACTIVE);

        verify(supportRepository).findHistoryInvolvingDepartmentAndStatus("ALPHA", SupportStatus.ACTIVE);
        verify(supportRepository, never()).findHistoryInvolvingDepartment(any());
        verify(supportRepository, never()).findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(any());
        verify(supportRepository, never()).findByStatus(any());
        verify(scopeResolver, times(1)).resolve();
    }

    @Test
    @DisplayName("An Admin keeps the global history queries and never uses the department ones")
    public void adminKeepsGlobalHistory() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(supportRepository.findAllByArchivedAtIsNullOrderByCreatedAtDesc()).thenReturn(List.of());
        when(supportRepository.findByStatus(any())).thenReturn(List.of());

        service.list(null);

        verify(scopeResolver, times(1)).resolve();
        verify(supportRepository).findAllByArchivedAtIsNullOrderByCreatedAtDesc();
        verify(supportRepository).findByStatus(SupportStatus.ACTIVE);   // the Admin read still reconciles every active record
        verify(supportRepository, never()).findByStatusInvolvingDepartment(any(), any());
        verify(supportRepository, never()).findHistoryInvolvingDepartment(any());
        verify(supportRepository, never()).findHistoryInvolvingDepartmentAndStatus(any(), any());
    }

    @Test
    @DisplayName("A refused scope (invalid Manager or no sign-in) stops options, preview and history before any repository is read")
    public void refusedScopeStopsEarly() {
        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        doThrow(refusal).when(scopeResolver).resolve();

        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.getOptions("ALPHA")));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.preview(planFor("ALPHA"))));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.list(null)));

        verifyNothingRead();
    }

    @Test
    @DisplayName("Staff scope is refused with 403 on options, preview and history before any repository is read")
    public void staffScopeIsRefused() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());

        for (Runnable call : List.<Runnable>of(() -> service.getOptions("ALPHA"), () -> service.preview(planFor("ALPHA")), () -> service.list(null))) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, call::run).getStatusCode());
        }
        verifyNothingRead();
    }

    @Test
    @DisplayName("A Manager asking for another department options gets 404 before anything is loaded, with the scope resolved once")
    public void otherDepartmentOptionsAreRefusedEarly() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.getOptions("BETA"));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        verify(scopeResolver, times(1)).resolve();
        verifyNothingRead();
    }

    @Test
    @DisplayName("A Manager previewing another receiving department gets the not-found answer before anything is loaded")
    public void otherDepartmentPreviewIsAnsweredEarly() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));

        SupportDtos.Preview preview = service.preview(planFor("BETA"));

        assertFalse(preview.valid());
        assertEquals("DEPARTMENT_NOT_FOUND", preview.problems().get(0).code());
        assertTrue(preview.items().isEmpty());
        assertTrue(preview.donors().isEmpty());
        verify(scopeResolver, times(1)).resolve();
        verifyNothingRead();
    }

    private void stubEmptyNarrowedData() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(departmentRepository.findAllByOrderByCreatedAtAscNameAsc()).thenReturn(List.of(alpha));
        when(assignmentRepository.findBusyStaffIds(any())).thenReturn(List.of());
        when(assignmentRepository.findHeldTaskIdsByDepartment(eq(alpha), any())).thenReturn(List.of());
        when(assignmentRepository.sumSupportAttributedMinutes(eq(alpha), eq("ALPHA"), any())).thenReturn(0L);
        when(staffRepository.findActiveWithUserByDepartment(alpha)).thenReturn(List.of());
        when(staffRepository.findActiveWithUserByAvailability(any())).thenReturn(List.of());
        when(taskRepository.findWaitingByDepartment(eq(alpha), any())).thenReturn(List.of());
        when(taskRepository.countWaitingByDepartmentCode(any())).thenReturn(List.of());
    }

    private void verifyNoGlobalLoad() {
        verify(staffRepository, never()).findAllWithUser();
        verify(staffRepository, never()).findAll();
        verify(taskRepository, never()).findByStatusIn(any());
        verify(taskRepository, never()).findAll();
        verify(assignmentRepository, never()).findAllActiveAssignments(any());
        verify(assignmentRepository, never()).findAll();
        verify(supportRepository, never()).findByStatus(any());
    }

    @Test
    @DisplayName("A Manager options use the narrowed queries and never load the global staff, tasks, assignments or support records; scope resolved once")
    public void managerOptionsUseNarrowedQueries() {
        stubEmptyNarrowedData();

        assertNotNull(service.getOptions("ALPHA"));

        verify(scopeResolver, times(1)).resolve();
        verify(taskRepository).findWaitingByDepartment(eq(alpha), any());
        verify(taskRepository).countWaitingByDepartmentCode(any());
        verify(assignmentRepository).findBusyStaffIds(any());
        verify(assignmentRepository).sumSupportAttributedMinutes(eq(alpha), eq("ALPHA"), any());
        verify(staffRepository).findActiveWithUserByDepartment(alpha);
        verifyNoGlobalLoad();
    }

    @Test
    @DisplayName("A Manager preview uses the narrowed queries, looks the named people up by id, and never loads the global datasets; scope resolved once")
    public void managerPreviewUsesNarrowedQueries() {
        stubEmptyNarrowedData();
        SupportPlanRequest request = planFor("ALPHA");
        UUID named = request.getItems().get(0).getStaffId();
        when(staffRepository.findAllById(any())).thenReturn(List.of());

        assertNotNull(service.preview(request));

        verify(scopeResolver, times(1)).resolve();
        verify(staffRepository).findAllById(List.of(named));
        verify(taskRepository).findWaitingByDepartment(eq(alpha), any());
        verifyNoGlobalLoad();
    }

    @Test
    @DisplayName("An Admin keeps the full load for options and preview and never uses the narrowed queries")
    public void adminKeepsTheFullLoad() {
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(departmentRepository.findAllByOrderByCreatedAtAscNameAsc()).thenReturn(List.of(alpha));
        when(supportRepository.findByStatus(any())).thenReturn(List.of());
        when(assignmentRepository.findAllActiveAssignments(any())).thenReturn(List.of());
        when(staffRepository.findAllWithUser()).thenReturn(List.of());
        when(taskRepository.findByStatusIn(any())).thenReturn(List.of());

        assertNotNull(service.getOptions("ALPHA"));
        assertNotNull(service.preview(planFor("ALPHA")));

        verify(staffRepository, times(2)).findAllWithUser();
        verify(assignmentRepository, times(2)).findAllActiveAssignments(any());
        verify(taskRepository, times(2)).findByStatusIn(any());
        verify(taskRepository, never()).findWaitingByDepartment(any(), any());
        verify(assignmentRepository, never()).findBusyStaffIds(any());
        verify(assignmentRepository, never()).sumSupportAttributedMinutes(any(), any(), any());
        verify(staffRepository, never()).findActiveWithUserByDepartment(any());
        verify(staffRepository, never()).findActiveWithUserByAvailability(any());
    }

    // ── write paths (Step 2G-3) ──────────────────────────────────────────────

    private void signedIn(String username) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    public void clearContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Confirm by a Manager, Staff or a refused scope is stopped before any repository is read; the scope is resolved once")
    public void confirmIsAdminOnly() {
        SupportPlanRequest request = planFor("ALPHA");
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.confirm(request)).getStatusCode());
        verify(scopeResolver, times(1)).resolve();

        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.confirm(request)).getStatusCode());

        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in");
        doThrow(refusal).when(scopeResolver).resolve();
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.confirm(request)));
        verifyNothingRead();
    }

    @Test
    @DisplayName("A Manager end uses the department-scoped id lookup and lock, never the global ones, and an unrelated record stops at 404 before any task is locked")
    public void managerEndUsesScopedLookups() {
        UUID id = UUID.randomUUID();
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(supportRepository.findTaskIdByIdInvolvingDepartment(id, "ALPHA")).thenReturn(java.util.Optional.empty());

        SupportPlanException thrown = assertThrows(SupportPlanException.class, () -> service.end(id));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatus());
        verify(scopeResolver, times(1)).resolve();
        verify(supportRepository).findTaskIdByIdInvolvingDepartment(id, "ALPHA");
        verify(supportRepository, never()).findTaskIdById(any());
        verify(supportRepository, never()).lockById(any());
        verify(supportRepository, never()).lockByIdInvolvingDepartment(any(), any());
        verifyNoInteractions(taskRepository, staffRepository, assignmentRepository);
    }

    @Test
    @DisplayName("A Manager end of an involved record locks it through the scoped query and never through the global lock")
    public void managerEndLocksThroughTheScopedQuery() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(supportRepository.findTaskIdByIdInvolvingDepartment(id, "ALPHA")).thenReturn(java.util.Optional.of(taskId));
        when(supportRepository.lockByIdInvolvingDepartment(id, "ALPHA")).thenReturn(java.util.Optional.empty());   // lost a race: gone by the time it is locked

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(SupportPlanException.class, () -> service.end(id)).getStatus());

        verify(taskRepository).lockByIds(java.util.Set.of(taskId));
        verify(supportRepository).lockByIdInvolvingDepartment(id, "ALPHA");
        verify(supportRepository, never()).findTaskIdById(any());
        verify(supportRepository, never()).lockById(any());
    }

    @Test
    @DisplayName("A Manager archive locks through the department-scoped query only")
    public void managerArchiveUsesScopedLock() {
        UUID id = UUID.randomUUID();
        signedIn("manager");
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.department(alpha));
        when(supportRepository.lockByIdInvolvingDepartment(id, "ALPHA")).thenReturn(java.util.Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(SupportPlanException.class, () -> service.archive(id)).getStatus());

        verify(scopeResolver, times(1)).resolve();
        verify(supportRepository).lockByIdInvolvingDepartment(id, "ALPHA");
        verify(supportRepository, never()).lockById(any());
        verifyNoInteractions(taskRepository, staffRepository, assignmentRepository);
    }

    @Test
    @DisplayName("An Admin end and archive keep the global lookups and never use the department-scoped ones")
    public void adminKeepsGlobalLookups() {
        UUID id = UUID.randomUUID();
        signedIn("admin");
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.all());
        when(supportRepository.findTaskIdById(id)).thenReturn(java.util.Optional.empty());
        when(supportRepository.lockById(id)).thenReturn(java.util.Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(SupportPlanException.class, () -> service.end(id)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(SupportPlanException.class, () -> service.archive(id)).getStatus());

        verify(supportRepository).findTaskIdById(id);
        verify(supportRepository).lockById(id);
        verify(supportRepository, never()).findTaskIdByIdInvolvingDepartment(any(), any());
        verify(supportRepository, never()).lockByIdInvolvingDepartment(any(), any());
    }

    @Test
    @DisplayName("End and archive by Staff scope or a refused scope are stopped before any repository is read")
    public void endAndArchiveRefusedEarly() {
        UUID id = UUID.randomUUID();
        signedIn("someone");
        when(scopeResolver.resolve()).thenReturn(DepartmentScope.own());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.end(id)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.archive(id)).getStatusCode());

        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        doThrow(refusal).when(scopeResolver).resolve();
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.end(id)));
        assertSame(refusal, assertThrows(ResponseStatusException.class, () -> service.archive(id)));
        verifyNothingRead();
    }
}
