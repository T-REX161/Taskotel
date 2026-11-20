package com.hotel.workload.security;

import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrant;
import com.hotel.workload.staff.ManagerViewGrantChange;
import com.hotel.workload.staff.ManagerViewGrantChangeRepository;
import com.hotel.workload.staff.ManagerViewGrantRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A role change is all or nothing. Not wrapped in a test transaction (the service's own transaction is what is under test), so the data is
 * really committed and cleaned up afterwards. The role history is replaced by a repository that fails when asked to write, which is the last
 * step of the change: everything the change did before it (the role, the session version, the revoked grants and their audit rows) must be
 * undone.
 */
@SpringBootTest
public class RoleChangeAtomicityTest {

    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private ManagerViewGrantChangeRepository grantChangeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleChangeService roleChangeService;
    @MockitoBean private UserRoleChangeRepository roleChanges;

    private ScopeFixtures fixtures;
    private User admin;
    private Staff manager;

    @BeforeEach
    void setUp() {
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department home = fixtures.department("AT_HOME");
        admin = fixtures.account("at_admin", Role.ADMIN, true);
        manager = fixtures.manager("at_manager", home);
        for (String code : List.of("AT_G1", "AT_G2")) {
            ManagerViewGrant grant = new ManagerViewGrant();
            grant.setManager(manager.getUser());
            grant.setDepartment(fixtures.department(code));
            grant.setGrantedByUserId(admin.getId());
            grant.setGrantedByUsername(admin.getUsername());
            grantRepository.saveAndFlush(grant);
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("at_admin", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        grantRepository.deleteAll(grantRepository.findByManagerId(manager.getUser().getId()));
        grantChangeRepository.deleteAll(grantChangeRepository.findByManagerUserIdOrderByChangedAtDesc(manager.getUser().getId()));
        fixtures.remove("at_manager", "at_admin");
        for (String code : List.of("AT_G1", "AT_G2", "AT_HOME")) {
            departmentRepository.findByCode(code).ifPresent(departmentRepository::delete);
        }
    }

    @Test
    @DisplayName("If the history cannot be written, the role, the session version, the revoked grants and their audit rows are all rolled back")
    void failureLeavesNoPartialChange() {
        Mockito.when(roleChanges.saveAndFlush(Mockito.any())).thenThrow(new IllegalStateException("history unavailable"));
        Mockito.when(roleChanges.save(Mockito.any())).thenThrow(new IllegalStateException("history unavailable"));

        assertThrows(IllegalStateException.class, () -> roleChangeService.changeRole(manager.getUser().getId(), Role.STAFF, null, "127.0.0.1"));

        User stored = userRepository.findById(manager.getUser().getId()).orElseThrow();
        assertEquals(Role.MANAGER, stored.getRole(), "the role is unchanged");
        assertNull(stored.getSessionsValidAfter(), "so are the sessions");
        assertEquals(2, grantRepository.findByManagerId(stored.getId()).size(), "no grant was revoked");
        assertTrue(grantChangeRepository.findByManagerUserIdOrderByChangedAtDesc(stored.getId()).stream()
                .noneMatch(change -> ManagerViewGrantChange.SOURCE_ROLE_CHANGE.equals(change.getChangeSource())), "no revocation audit row was left behind");
        assertTrue(staffRepository.findByUserUsername("at_manager").isPresent());
    }

    @Test
    @DisplayName("When everything works, the role, the revoked grants, their audit rows and the history are committed together")
    void successCommitsEverything() {
        Mockito.when(roleChanges.saveAndFlush(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(roleChanges.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        roleChangeService.changeRole(manager.getUser().getId(), Role.STAFF, null, "127.0.0.1");

        User stored = userRepository.findById(manager.getUser().getId()).orElseThrow();
        assertEquals(Role.STAFF, stored.getRole());
        assertTrue(stored.getSessionsValidAfter() != null);
        assertTrue(grantRepository.findByManagerId(stored.getId()).isEmpty());
        assertEquals(2, grantChangeRepository.findByManagerUserIdOrderByChangedAtDesc(stored.getId()).stream()
                .filter(change -> ManagerViewGrantChange.SOURCE_ROLE_CHANGE.equals(change.getChangeSource())).count());
        Mockito.verify(roleChanges).saveAndFlush(Mockito.argThat(record -> record.getRevokedGrants() == 2 && record.getNewRole() == Role.STAFF
                && record.getPreviousRole() == Role.MANAGER && "at_admin".equals(record.getChangedByUsername())));
    }
}
