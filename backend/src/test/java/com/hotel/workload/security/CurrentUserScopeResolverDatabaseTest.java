package com.hotel.workload.security;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scope resolver against the real (H2) database through the real repositories: realistic accounts, Staff records and
 * departments, including changes made between two calls. Every test rolls back.
 */
@SpringBootTest
@Transactional
public class CurrentUserScopeResolverDatabaseTest {

    @Autowired private CurrentUserScopeResolver resolver;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Department alpha;
    private Department beta;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        alpha = newDepartment("SCOPE_ALPHA", true);
        beta = newDepartment("SCOPE_BETA", true);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Department newDepartment(String code, boolean active) {
        Department department = new Department(code, code + " name");
        department.setActive(active);
        department.setCreatedAt(LocalDateTime.now());
        return departmentRepository.saveAndFlush(department);
    }

    private User newUser(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(role);
        user.setActive(active);
        return userRepository.saveAndFlush(user);
    }

    private Staff newStaff(User user, Department department, boolean active) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Staff " + user.getUsername());
        staff.setEmail(user.getUsername() + "@hotel.test");
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(active);
        return staffRepository.saveAndFlush(staff);
    }

    /** A Manager account with a Staff record in the department; returns the account. */
    private User newManager(String username, Department department) {
        User user = newUser(username, Role.MANAGER, true);
        newStaff(user, department, true);
        return user;
    }

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void assertRefused(String username) {
        signInAs(username, "MANAGER");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> resolver.resolve());
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertEquals(CurrentUserScopeResolver.MANAGER_NOT_ASSIGNED_MESSAGE, error.getReason());
    }

    @Test
    @DisplayName("An active Admin with no Staff record gets the global scope")
    public void adminWithoutStaffRecord() {
        newUser("scope_admin", Role.ADMIN, true);
        assertTrue(staffRepository.findByUserUsername("scope_admin").isEmpty());

        signInAs("scope_admin", "ADMIN");

        DepartmentScope scope = resolver.resolve();
        assertTrue(scope.isAll());
        assertTrue(scope.includes(alpha) && scope.includes(beta));
    }

    @Test
    @DisplayName("An active Manager with an active Staff record and an active department gets that department, read from the database")
    public void validManager() {
        newManager("scope_mia", alpha);
        signInAs("scope_mia", "MANAGER");

        DepartmentScope scope = resolver.resolve();

        assertTrue(scope.isDepartment());
        assertEquals("SCOPE_ALPHA", scope.departmentCode());
        assertEquals(alpha.getId(), scope.department().getId());
        assertTrue(scope.includes(alpha));
        assertFalse(scope.includes(beta));
    }

    @Test
    @DisplayName("Two Managers of the same department share a scope; Managers of different departments do not")
    public void sameAndDifferentDepartments() {
        newManager("scope_m1", alpha);
        newManager("scope_m2", alpha);
        newManager("scope_m3", beta);

        signInAs("scope_m1", "MANAGER");
        DepartmentScope first = resolver.resolve();
        signInAs("scope_m2", "MANAGER");
        DepartmentScope second = resolver.resolve();
        signInAs("scope_m3", "MANAGER");
        DepartmentScope other = resolver.resolve();

        assertEquals(first, second);
        assertNotEquals(first, other);
        assertEquals("SCOPE_BETA", other.departmentCode());
        assertFalse(other.includes(alpha));
    }

    @Test
    @DisplayName("A Manager without a linked Staff record is refused with 403")
    public void managerWithoutStaffRecord() {
        newUser("scope_nostaff", Role.MANAGER, true);

        assertRefused("scope_nostaff");
    }

    @Test
    @DisplayName("A Manager whose User account is inactive is refused with 403")
    public void managerWithInactiveUser() {
        User user = newUser("scope_inactive_user", Role.MANAGER, false);
        newStaff(user, alpha, true);

        assertRefused("scope_inactive_user");
    }

    @Test
    @DisplayName("A Manager whose Staff record is inactive is refused with 403")
    public void managerWithInactiveStaff() {
        User user = newUser("scope_inactive_staff", Role.MANAGER, true);
        newStaff(user, alpha, false);

        assertRefused("scope_inactive_staff");
    }

    @Test
    @DisplayName("A Manager whose department is inactive is refused with 403")
    public void managerWithInactiveDepartment() {
        Department closed = newDepartment("SCOPE_CLOSED", false);
        newManager("scope_closed", closed);

        assertRefused("scope_closed");
    }

    @Test
    @DisplayName("A Staff record may now be stored without a department (V19), and a Manager whose record has none is still refused with 403")
    public void managerWithoutDepartmentIsStillRefused() {
        User user = newUser("scope_nodept", Role.MANAGER, true);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("No department");
        staff.setEmail("scope_nodept@hotel.test");
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);

        // The schema no longer forbids it (an operational STAFF account may have no department yet), so for a Manager it is the resolver's
        // refusal that keeps the rule: the Manager's scope is their department, and without one there is none
        staffRepository.saveAndFlush(staff);
        assertRefused("scope_nodept");
    }

    @Test
    @DisplayName("Changes in the database apply on the very next call: a moved Manager, then a deactivated department, account or Staff record")
    public void changesApplyImmediately() {
        User user = newManager("scope_move", alpha);
        Staff staff = staffRepository.findByUserUsername("scope_move").orElseThrow();
        signInAs("scope_move", "MANAGER");
        assertEquals("SCOPE_ALPHA", resolver.resolve().departmentCode());

        // An Admin moves the Manager to another department: the next request is scoped to the new one
        staff.setDepartment(beta);
        staffRepository.saveAndFlush(staff);
        assertEquals("SCOPE_BETA", resolver.resolve().departmentCode());

        // The department is deactivated
        beta.setActive(false);
        departmentRepository.saveAndFlush(beta);
        assertRefused("scope_move");

        // ...and reactivated
        beta.setActive(true);
        departmentRepository.saveAndFlush(beta);
        signInAs("scope_move", "MANAGER");
        assertEquals("SCOPE_BETA", resolver.resolve().departmentCode());

        // The Staff record is deactivated
        staff.setActive(false);
        staffRepository.saveAndFlush(staff);
        assertRefused("scope_move");
        staff.setActive(true);
        staffRepository.saveAndFlush(staff);

        // The account is deactivated
        user.setActive(false);
        userRepository.saveAndFlush(user);
        assertRefused("scope_move");
    }

    @Test
    @DisplayName("The database role wins: an account that is a Staff member in the database is refused even with a Manager authority")
    public void databaseRoleWins() {
        User user = newUser("scope_demoted", Role.STAFF, true);
        newStaff(user, alpha, true);

        assertRefused("scope_demoted");
    }

    @Test
    @DisplayName("Staff get the own-records scope and no department scope")
    public void staffGetOwnScope() {
        User user = newUser("scope_staff", Role.STAFF, true);
        newStaff(user, alpha, true);
        signInAs("scope_staff", "STAFF");

        DepartmentScope scope = resolver.resolve();

        assertTrue(scope.isOwn());
        assertFalse(scope.includes(alpha));
        assertFalse(scope.isAll());
    }

    @Test
    @DisplayName("Without authentication the resolver answers 401")
    public void unauthenticated() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> resolver.resolve());
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }
}
