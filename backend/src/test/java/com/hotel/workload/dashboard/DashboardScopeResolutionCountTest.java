package com.hotel.workload.dashboard;

import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.workload.WorkloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * One request, one scope snapshot: the dashboard resolves the caller's scope exactly once (the workload buckets reuse it), while a direct
 * workload request still resolves and enforces its own scope. The real resolver is wrapped in a spy that counts its calls.
 */
@SpringBootTest
@Transactional
public class DashboardScopeResolutionCountTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WorkloadService workloadService;
    @MockitoSpyBean private CurrentUserScopeResolver scopeResolver;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department dept = fixtures.department("SRC_DEPT");
        fixtures.account("src_admin", Role.ADMIN, true);
        fixtures.manager("src_mgr", dept);
        fixtures.staff("src_staff", dept);
        clearInvocations(scopeResolver);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private int status(String path, String username, String role) throws Exception {
        clearInvocations(scopeResolver);
        return mockMvc.perform(get(path).with(user(username).roles(role))).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("A dashboard request resolves the scope once for Admin, Manager and Staff")
    public void dashboardResolvesOnce() throws Exception {
        for (String[] caller : new String[][] {{"src_admin", "ADMIN"}, {"src_mgr", "MANAGER"}, {"src_staff", "STAFF"}}) {
            assertEquals(200, status("/api/dashboard/summary", caller[0], caller[1]), caller[0]);
            verify(scopeResolver, times(1)).resolve();
        }
    }

    @Test
    @DisplayName("A direct workload request still resolves its own scope, once, and the scope is enforced")
    public void workloadResolvesItsOwnScope() throws Exception {
        for (String[] caller : new String[][] {{"src_admin", "ADMIN"}, {"src_mgr", "MANAGER"}, {"src_staff", "STAFF"}}) {
            assertEquals(200, status("/api/workload", caller[0], caller[1]), caller[0]);
            verify(scopeResolver, times(1)).resolve();
        }
        // an invalid Manager is still refused by that own resolution
        new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).account("src_nostaff", Role.MANAGER, true);
        assertEquals(403, status("/api/workload", "src_nostaff", "MANAGER"));
        verify(scopeResolver, times(1)).resolve();
    }

    @Test
    @DisplayName("A direct service call to calculateWorkload() resolves the scope itself (and refuses with no sign-in)")
    public void directWorkloadCallResolves() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("src_admin", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        clearInvocations(scopeResolver);
        workloadService.calculateWorkload();
        verify(scopeResolver, times(1)).resolve();
        SecurityContextHolder.clearContext();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> workloadService.calculateWorkload());
    }
}
