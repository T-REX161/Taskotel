package com.hotel.workload.security;

import com.hotel.workload.staff.CreateDepartmentRequest;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.DepartmentService;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.UpdateDepartmentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 2I (R2): the Admin-only services refuse everyone else by themselves, without the URL rules. Each call goes straight to the
 * service bean (no HTTP), as other server code could. Every test rolls back.
 */
@SpringBootTest
@Transactional
public class DirectServiceAuthorizationTest {

    @Autowired private DepartmentService departmentService;
    @Autowired private UserService userService;
    @Autowired private UserAccountService accountService;
    @Autowired private CurrentUserScopeResolver scopeResolver;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Department alpha;
    private User target;

    @BeforeEach
    public void setUp() {
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department("DSA_ALPHA");
        fixtures.account("dsa_admin", Role.ADMIN, true);
        fixtures.account("dsa_admin2", Role.ADMIN, true);
        fixtures.manager("dsa_manager", alpha);
        fixtures.staff("dsa_staff", alpha);
        fixtures.account("dsa_manager_nostaff", Role.MANAGER, true);
        target = fixtures.account("dsa_target", Role.STAFF, true);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static int statusOf(Supplier<?> call) {
        return assertThrows(ResponseStatusException.class, call::get).getStatusCode().value();
    }

    private CreateDepartmentRequest newDepartment() {
        CreateDepartmentRequest request = new CreateDepartmentRequest();
        request.setName("Direct Service Probe");
        return request;
    }

    private UpdateDepartmentRequest renamed() {
        UpdateDepartmentRequest request = new UpdateDepartmentRequest();
        request.setName("Renamed By Mistake");
        request.setActive(true);
        return request;
    }

    /** Every Admin-only operation, as a call that would succeed for an Admin. Each returns something so it can be passed as a Supplier. */
    private List<Supplier<Object>> adminOnlyOperations() {
        return List.of(
                () -> departmentService.createDepartment(newDepartment()),
                () -> departmentService.updateDepartment("DSA_ALPHA", renamed()),
                () -> { departmentService.deleteDepartment("DSA_ALPHA"); return null; },
                () -> userService.getAllUsers(),
                () -> accountService.deactivate(target.getId()),
                () -> accountService.activate(target.getId()),
                () -> { accountService.remove(target.getId()); return null; });
    }

    @Test
    @DisplayName("Staff, a valid Manager and an invalid Manager are refused with 403 by every Admin-only service operation")
    public void nonAdminsAreRefused() {
        for (String[] caller : new String[][]{{"dsa_staff", "STAFF"}, {"dsa_manager", "MANAGER"}, {"dsa_manager_nostaff", "MANAGER"}}) {
            signInAs(caller[0], caller[1]);
            for (Supplier<Object> operation : adminOnlyOperations()) {
                assertEquals(403, statusOf(operation::get), caller[0]);
            }
        }
        assertTrue(userRepository.findById(target.getId()).orElseThrow().isActive(), "the account was not touched");
        assertEquals("DSA_ALPHA name", departmentRepository.findByCode("DSA_ALPHA").orElseThrow().getName());
        assertFalse(departmentRepository.existsByNameIgnoreCase("Direct Service Probe"));
    }

    @Test
    @DisplayName("With nobody signed in, every Admin-only service operation answers 401")
    public void anonymousIsRefused() {
        SecurityContextHolder.clearContext();
        for (Supplier<Object> operation : adminOnlyOperations()) {
            assertEquals(401, statusOf(operation::get));
        }
        assertTrue(userRepository.findById(target.getId()).orElseThrow().isActive());
    }

    @Test
    @DisplayName("An Admin still performs all of them: the business rules are unchanged")
    public void adminStillWorks() {
        signInAs("dsa_admin", "ADMIN");
        assertNotNull(departmentService.createDepartment(newDepartment()));
        assertEquals("Renamed By Mistake", departmentService.updateDepartment("DSA_ALPHA", renamed()).getName());
        assertFalse(userService.getAllUsers().isEmpty());
        assertFalse(accountService.deactivate(target.getId()).isActive());
        assertTrue(accountService.activate(target.getId()).isActive());
    }

    @Test
    @DisplayName("The acting identity comes from the security context: an Admin cannot deactivate or remove their own account")
    public void actingIdentityComesFromTheContext() {
        UUID ownId = userRepository.findByUsername("dsa_admin").orElseThrow().getId();
        signInAs("dsa_admin", "ADMIN");
        assertEquals(400, statusOf(() -> accountService.deactivate(ownId)));
        assertEquals(400, statusOf(() -> { accountService.remove(ownId); return null; }));
        // Another Admin is a different identity, so the same call is allowed for them (the last-Admin rule still holds separately)
        signInAs("dsa_admin2", "ADMIN");
        assertDoesNotThrow(() -> accountService.deactivate(ownId));
    }

    @Test
    @DisplayName("No service method accepts the acting user name from the caller")
    public void noCallerSuppliedIdentity() {
        for (Method method : UserAccountService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            for (Class<?> type : method.getParameterTypes()) {
                assertNotEquals(String.class, type, method.getName() + " must not take a user name; it comes from the security context");
            }
        }
    }

    @Test
    @DisplayName("requireAdmin on the resolver: Admin passes, others are refused, no sign-in is 401")
    public void resolverRequireAdmin() {
        signInAs("dsa_admin", "ADMIN");
        assertTrue(scopeResolver.requireAdmin().isAll());
        assertEquals("dsa_admin", scopeResolver.currentUsername());

        signInAs("dsa_manager", "MANAGER");
        assertEquals(403, statusOf(scopeResolver::requireAdmin));
        signInAs("dsa_staff", "STAFF");
        assertEquals(403, statusOf(scopeResolver::requireAdmin));
        signInAs("dsa_manager_nostaff", "MANAGER");
        assertEquals(403, statusOf(scopeResolver::requireAdmin));

        SecurityContextHolder.clearContext();
        assertEquals(401, statusOf(scopeResolver::requireAdmin));
        assertEquals(401, statusOf(scopeResolver::currentUsername));
    }
}
