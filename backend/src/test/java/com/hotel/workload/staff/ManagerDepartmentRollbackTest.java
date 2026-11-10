package com.hotel.workload.staff;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction rollback of the Manager department change. This test is NOT wrapped in a test transaction, so the service transaction
 * really commits or rolls back; it creates its own rows (committed) and removes them afterwards.
 */
@SpringBootTest
public class ManagerDepartmentRollbackTest {

    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ManagerDepartmentChangeRepository changeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ManagerDepartmentService service;

    private ScopeFixtures fixtures() {
        return new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
    }

    @AfterEach
    public void cleanUp() {
        SecurityContextHolder.clearContext();
        changeRepository.deleteAll(changeRepository.findAll().stream().filter(c -> c.getManagerUsername().startsWith("mdr_")).toList());
        fixtures().remove("mdr_m1", "mdr_admin");
        departmentRepository.findByCode("MDR_ALPHA").ifPresent(departmentRepository::delete);
        departmentRepository.findByCode("MDR_BETA").ifPresent(departmentRepository::delete);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("If the audit record cannot be written, the department change is rolled back: no change without a record")
    public void failedAuditRollsTheChangeBack() {
        ScopeFixtures fixtures = fixtures();
        Department alpha = fixtures.department("MDR_ALPHA");
        fixtures.department("MDR_BETA");
        Staff manager = fixtures.manager("mdr_m1", alpha);

        // An Admin authority whose account does not exist: the department change goes ahead, the audit record cannot be attributed
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("mdr_ghost_admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThrows(IllegalStateException.class, () -> service.assign(manager.getUser().getId(), "MDR_BETA"));

        assertEquals("MDR_ALPHA", staffRepository.findById(manager.getId()).orElseThrow().getDepartment().getCode(),
                "the department change was rolled back");
        assertTrue(changeRepository.findHistory(manager.getUser().getId()).isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("A successful change is committed together with its audit record")
    public void successfulChangeCommitsBoth() {
        ScopeFixtures fixtures = fixtures();
        Department alpha = fixtures.department("MDR_ALPHA");
        fixtures.department("MDR_BETA");
        Staff manager = fixtures.manager("mdr_m1", alpha);
        fixtures.account("mdr_admin", Role.ADMIN, true);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("mdr_admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service.assign(manager.getUser().getId(), "MDR_BETA");

        assertEquals("MDR_BETA", staffRepository.findById(manager.getId()).orElseThrow().getDepartment().getCode());
        assertEquals(1, changeRepository.findHistory(manager.getUser().getId()).size());
    }
}
