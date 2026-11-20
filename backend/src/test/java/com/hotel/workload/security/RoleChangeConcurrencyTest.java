package com.hotel.workload.security;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent role changes are decided one at a time. Real threads, real (committed, then cleaned up) data: not wrapped in a test
 * transaction, because the locking inside the service's own transaction is what is under test.
 */
@SpringBootTest
public class RoleChangeConcurrencyTest {

    private static final String PASSWORD = "password";

    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRoleChangeRepository roleChanges;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleChangeService roleChangeService;

    private ScopeFixtures fixtures;
    private Department home;
    private final List<UUID> otherActiveAdmins = new ArrayList<>();
    private final List<UUID> touched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department("CC_HOME");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        for (UUID id : touched) roleChanges.deleteAll(roleChanges.findByTargetUserIdOrderByChangedAtDesc(id));
        fixtures.remove("cc_a", "cc_b", "cc_x");
        for (UUID id : otherActiveAdmins) userRepository.findById(id).ifPresent(user -> {
            user.setActive(true);
            userRepository.saveAndFlush(user);
        });
        departmentRepository.findByCode("CC_HOME").ifPresent(departmentRepository::delete);
    }

    /** Switches every other active administrator off (restored in cleanUp), so "the last active administrator" is real. */
    private void onlyActiveAdmins(User... keep) {
        List<UUID> kept = List.of(keep).stream().map(User::getId).toList();
        for (User other : userRepository.findAll()) {
            if (other.getRole() == Role.ADMIN && other.isActive() && !kept.contains(other.getId())) {
                otherActiveAdmins.add(other.getId());
                other.setActive(false);
                userRepository.saveAndFlush(other);
            }
        }
    }

    private Callable<Object> asAdmin(String username, UUID target, Role role, CountDownLatch start) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            try {
                start.await(10, TimeUnit.SECONDS);
                return roleChangeService.changeRole(target, role, PASSWORD, "10.0.0." + username.hashCode() % 200);
            } catch (ResponseStatusException refused) {
                return refused;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private List<Object> race(CountDownLatch start, Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = pool.submit(first);
            Future<Object> two = pool.submit(second);
            start.countDown(); // both threads are waiting: they go at the same moment
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Two administrators demoting each other at the same moment: exactly one change succeeds and an active administrator always remains")
    void crossDemotionLeavesOneAdministrator() throws Exception {
        User a = fixtures.staffRecord("cc_a", Role.ADMIN, home).getUser();
        User b = fixtures.staffRecord("cc_b", Role.ADMIN, home).getUser();
        touched.add(a.getId());
        touched.add(b.getId());
        onlyActiveAdmins(a, b);

        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = race(start, asAdmin("cc_a", b.getId(), Role.STAFF, start), asAdmin("cc_b", a.getId(), Role.STAFF, start));

        long succeeded = results.stream().filter(r -> r instanceof UserDto).count();
        assertEquals(1, succeeded, "exactly one demotion wins: " + results);
        for (Object r : results) {
            if (r instanceof ResponseStatusException refused) {
                assertTrue(refused.getStatusCode().value() == 403 || refused.getStatusCode().value() == 409, "the loser is refused: " + refused.getStatusCode());
            }
        }
        long admins = List.of(a.getId(), b.getId()).stream().map(id -> userRepository.findById(id).orElseThrow())
                .filter(u -> u.getRole() == Role.ADMIN && u.isActive()).count();
        assertEquals(1, admins, "one of them is still an active administrator");
        assertEquals(1, roleChanges.findByTargetUserIdOrderByChangedAtDesc(a.getId()).size() + roleChanges.findByTargetUserIdOrderByChangedAtDesc(b.getId()).size(), "one history record");
    }

    @Test
    @DisplayName("Two changes to the same account at the same moment are applied one after the other: the history forms an unbroken chain and the final role is the last one")
    void sameTargetChangesAreSerialised() throws Exception {
        User a = fixtures.staffRecord("cc_a", Role.ADMIN, home).getUser();
        User b = fixtures.staffRecord("cc_b", Role.ADMIN, home).getUser();
        User x = fixtures.staff("cc_x", home).getUser();
        touched.add(x.getId());
        onlyActiveAdmins(a, b);

        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = race(start, asAdmin("cc_a", x.getId(), Role.MANAGER, start), asAdmin("cc_b", x.getId(), Role.ADMIN, start));

        assertEquals(2, results.stream().filter(r -> r instanceof UserDto).count(), "both are valid in either order: " + results);
        List<UserRoleChange> history = new ArrayList<>(roleChanges.findByTargetUserIdOrderByChangedAtDesc(x.getId()));
        history.sort(Comparator.comparing(UserRoleChange::getChangedAt));
        assertEquals(2, history.size());
        assertEquals(Role.STAFF, history.get(0).getPreviousRole(), "the first change started from the original role");
        assertEquals(history.get(0).getNewRole(), history.get(1).getPreviousRole(), "the second started from where the first ended: no lost update");
        User stored = userRepository.findById(x.getId()).orElseThrow();
        assertEquals(history.get(1).getNewRole(), stored.getRole());
        assertTrue(JwtUtil.sessionVersion(stored.getSessionsValidAfter()) > 0);
    }
}
