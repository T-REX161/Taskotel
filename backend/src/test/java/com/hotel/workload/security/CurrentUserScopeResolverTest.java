package com.hotel.workload.security;

import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the scope resolver's decision table, with the two repositories mocked. The same rules are exercised against a real
 * database in {@link CurrentUserScopeResolverDatabaseTest}.
 */
public class CurrentUserScopeResolverTest {

    private UserRepository userRepository;
    private StaffRepository staffRepository;
    private CurrentUserScopeResolver resolver;

    @BeforeEach
    public void setUp() {
        userRepository = mock(UserRepository.class);
        staffRepository = mock(StaffRepository.class);
        resolver = new CurrentUserScopeResolver(userRepository, staffRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Authentication signedIn(String username, String... roles) {
        return new UsernamePasswordAuthenticationToken(username, null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static Department department(String code, boolean active) {
        Department department = new Department(code, code + " name");
        department.setActive(active);
        return department;
    }

    private User user(String username, Role role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        user.setActive(active);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    private Staff staffFor(String username, User user, Department department, boolean active) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName(username);
        staff.setEmail(username + "@hotel.test");
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(active);
        when(staffRepository.findByUserUsername(username)).thenReturn(Optional.of(staff));
        return staff;
    }

    private ResponseStatusException refused(Authentication authentication) {
        return assertThrows(ResponseStatusException.class, () -> resolver.resolve(authentication));
    }

    // ── ADMIN ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An active Admin with no Staff record gets the global scope, and no repository is consulted")
    public void adminWithoutStaffRecordGetsAll() {
        DepartmentScope scope = resolver.resolve(signedIn("root", "ADMIN"));

        assertTrue(scope.isAll());
        assertFalse(scope.isDepartment());
        assertFalse(scope.isOwn());
        assertTrue(scope.includes(department("ANY", true)));
        assertTrue(scope.includes(null), "A global scope also includes records that have no department");
        verifyNoInteractions(userRepository, staffRepository);
    }

    // ── MANAGER: valid ───────────────────────────────────────────────────────

    @Test
    @DisplayName("An active Manager with an active Staff record and an active Department gets exactly that department")
    public void validManagerGetsTheirDepartment() {
        Department front = department("FRONT_DESK", true);
        staffFor("mia", user("mia", Role.MANAGER, true), front, true);

        DepartmentScope scope = resolver.resolve(signedIn("mia", "MANAGER"));

        assertTrue(scope.isDepartment());
        assertEquals("FRONT_DESK", scope.departmentCode());
        assertSame(front, scope.department());
        assertTrue(scope.includes(front));
        assertTrue(scope.includesCode("FRONT_DESK"));
        assertFalse(scope.includes(department("HOUSEKEEPING", true)), "Another department is outside the scope");
        assertFalse(scope.includes(null), "A record with no department is outside a department scope");
        assertFalse(scope.includesCode(null));
    }

    @Test
    @DisplayName("Two Managers of the same department get the same scope")
    public void managersOfTheSameDepartmentShareTheScope() {
        Department front = department("FRONT_DESK", true);
        staffFor("mia", user("mia", Role.MANAGER, true), front, true);
        staffFor("max", user("max", Role.MANAGER, true), front, true);

        DepartmentScope first = resolver.resolve(signedIn("mia", "MANAGER"));
        DepartmentScope second = resolver.resolve(signedIn("max", "MANAGER"));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("FRONT_DESK", second.departmentCode());
    }

    @Test
    @DisplayName("Managers of different departments get different scopes, and neither reaches the other's department")
    public void managersOfDifferentDepartmentsGetDifferentScopes() {
        Department front = department("FRONT_DESK", true);
        Department housekeeping = department("HOUSEKEEPING", true);
        staffFor("mia", user("mia", Role.MANAGER, true), front, true);
        staffFor("hal", user("hal", Role.MANAGER, true), housekeeping, true);

        DepartmentScope miaScope = resolver.resolve(signedIn("mia", "MANAGER"));
        DepartmentScope halScope = resolver.resolve(signedIn("hal", "MANAGER"));

        assertNotEquals(miaScope, halScope);
        assertTrue(miaScope.includes(front) && !miaScope.includes(housekeeping));
        assertTrue(halScope.includes(housekeeping) && !halScope.includes(front));
    }

    // ── MANAGER: every invalid state fails closed with 403 ───────────────────

    @Test
    @DisplayName("A Manager without a linked Staff record is refused with 403")
    public void managerWithoutStaffRecordIsRefused() {
        user("mia", Role.MANAGER, true);
        when(staffRepository.findByUserUsername("mia")).thenReturn(Optional.empty());

        ResponseStatusException error = refused(signedIn("mia", "MANAGER"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    @DisplayName("A Manager whose User account is inactive is refused with 403")
    public void managerWithInactiveAccountIsRefused() {
        User inactive = user("mia", Role.MANAGER, false);
        staffFor("mia", inactive, department("FRONT_DESK", true), true);

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("mia", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("A Manager whose Staff record is inactive is refused with 403")
    public void managerWithInactiveStaffRecordIsRefused() {
        staffFor("mia", user("mia", Role.MANAGER, true), department("FRONT_DESK", true), false);

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("mia", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("A Manager whose Staff record has no department is refused with 403")
    public void managerWithoutDepartmentIsRefused() {
        staffFor("mia", user("mia", Role.MANAGER, true), null, true);

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("mia", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("A Manager whose department is inactive is refused with 403")
    public void managerWithInactiveDepartmentIsRefused() {
        staffFor("mia", user("mia", Role.MANAGER, true), department("FRONT_DESK", false), true);

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("mia", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("A Manager authority with no matching account in the database is refused with 403")
    public void managerWithoutAccountRowIsRefused() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("ghost", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("A Manager authority whose account is no longer a Manager in the database is refused: the database role wins over the authority")
    public void managerAuthorityWithDifferentDatabaseRoleIsRefused() {
        User demoted = user("mia", Role.STAFF, true);
        staffFor("mia", demoted, department("FRONT_DESK", true), true);

        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("mia", "MANAGER")).getStatusCode());
    }

    @Test
    @DisplayName("Every refused Manager gets the same generic message, which does not reveal which check failed")
    public void refusedManagersAllSeeTheSameMessage() {
        // no staff record
        user("a", Role.MANAGER, true);
        when(staffRepository.findByUserUsername("a")).thenReturn(Optional.empty());
        // inactive account
        staffFor("b", user("b", Role.MANAGER, false), department("X", true), true);
        // inactive staff record
        staffFor("c", user("c", Role.MANAGER, true), department("X", true), false);
        // no department
        staffFor("d", user("d", Role.MANAGER, true), null, true);
        // inactive department
        staffFor("e", user("e", Role.MANAGER, true), department("X", false), true);

        for (String name : List.of("a", "b", "c", "d", "e")) {
            ResponseStatusException error = refused(signedIn(name, "MANAGER"));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode(), name);
            assertEquals(CurrentUserScopeResolver.MANAGER_NOT_ASSIGNED_MESSAGE, error.getReason(), name);
        }
    }

    // ── authentication ───────────────────────────────────────────────────────

    @Test
    @DisplayName("No authentication at all is refused with 401")
    public void missingAuthenticationIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, refused(null).getStatusCode());
        // and through the Spring Security context, which is empty here
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> resolver.resolve());
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(userRepository, staffRepository);
    }

    @Test
    @DisplayName("An anonymous or not-yet-authenticated token is refused with 401")
    public void anonymousAndUnauthenticatedTokensAreUnauthorized() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertEquals(HttpStatus.UNAUTHORIZED, refused(anonymous).getStatusCode());

        Authentication notAuthenticated = new UsernamePasswordAuthenticationToken("mia", "secret");
        assertFalse(notAuthenticated.isAuthenticated());
        assertEquals(HttpStatus.UNAUTHORIZED, refused(notAuthenticated).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, refused(signedIn("  ", "MANAGER")).getStatusCode(), "A blank user name is not a user");
        verifyNoInteractions(userRepository, staffRepository);
    }

    @Test
    @DisplayName("resolve() reads the authentication from the Spring Security context")
    public void resolveReadsTheSecurityContext() {
        Department front = department("FRONT_DESK", true);
        staffFor("mia", user("mia", Role.MANAGER, true), front, true);
        SecurityContextHolder.getContext().setAuthentication(signedIn("mia", "MANAGER"));

        assertEquals("FRONT_DESK", resolver.resolve().departmentCode());

        SecurityContextHolder.getContext().setAuthentication(signedIn("root", "ADMIN"));
        assertTrue(resolver.resolve().isAll());
    }

    // ── STAFF and unsupported roles ──────────────────────────────────────────

    @Test
    @DisplayName("Staff get the own-records scope: neither global nor department-level, and no repository is consulted")
    public void staffGetOwnScopeOnly() {
        DepartmentScope scope = resolver.resolve(signedIn("sam", "STAFF"));

        assertTrue(scope.isOwn());
        assertFalse(scope.isAll());
        assertFalse(scope.isDepartment());
        assertFalse(scope.includes(department("FRONT_DESK", true)), "A Staff scope includes no department");
        assertFalse(scope.includes(null));
        assertThrows(IllegalStateException.class, scope::department);
        verifyNoInteractions(userRepository, staffRepository);
    }

    @Test
    @DisplayName("A signed-in user with no recognised role is refused with 403")
    public void unknownRoleIsRefused() {
        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("pat", "GUEST")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, refused(signedIn("pat")).getStatusCode(), "No authority at all");
        verifyNoInteractions(userRepository, staffRepository);
    }

    // ── the scope value itself ───────────────────────────────────────────────

    @Test
    @DisplayName("A department scope cannot be built without a department, and the global and own scopes have no department")
    public void scopeValueRules() {
        assertThrows(IllegalArgumentException.class, () -> DepartmentScope.department(null));
        assertThrows(IllegalArgumentException.class, () -> DepartmentScope.department(new Department()));
        assertThrows(IllegalStateException.class, () -> DepartmentScope.all().department());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.own().departmentCode());
        assertNotEquals(DepartmentScope.all(), DepartmentScope.own());
        assertNotEquals(DepartmentScope.all(), DepartmentScope.department(department("FRONT_DESK", true)));
    }
}
