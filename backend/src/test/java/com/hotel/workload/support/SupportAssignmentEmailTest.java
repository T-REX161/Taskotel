package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Cross-department support (an Admin confirms that staff of another department help with a task): the helper, the Manager of the task's OWN
 * department and the confirming Admin are told, once each, after the commit; the helper's home-department Manager and other Admins are not.
 * Real transactions (an email goes out only after the commit); SendGrid is a mock.
 */
@SpringBootTest
public class SupportAssignmentEmailTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private SupportTestFixture fx;
    private ScopeFixtures scope;
    private static final String[] ACCOUNTS = {"sup7_admin", "sup7_admin2", "sup7_mgr_recv", "sup7_mgr_donor"};

    @BeforeEach
    void setUp() {
        when(emailService.isConfigured()).thenReturn(true);
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        scope = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        User admin = scope.account("sup7_admin", Role.ADMIN, true);
        scope.account("sup7_admin2", Role.ADMIN, true);
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(true);
        row.setCapacityAlertEnabled(true);
        row.setCrossDepartmentAssistanceEnabled(true);
        row.setCapacityThresholdPercent(85);
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedByUserId(admin.getId());
        row.setUpdatedByUsername(admin.getUsername());
        settingsRepository.saveAndFlush(row);
        SupportTestFixture.signInAs("sup7_admin", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
        scope.remove(ACCOUNTS);
        fx.cleanUp();
    }

    private void verified(String username) {
        User account = users.findByUsername(username).orElseThrow();
        account.setEmailVerified(true);
        users.saveAndFlush(account);
    }

    private List<String> recipients() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .map(i -> (String) i.getArgument(0)).sorted().collect(Collectors.toList());
    }

    @Test
    @DisplayName("Confirming support: the helper, the Manager of the task's own department and the confirming Admin are emailed once each; the donor's Manager and other Admins are not")
    void confirmedSupportRecipients() {
        var s = fx.overloaded(3, 3);
        scope.manager("sup7_mgr_recv", s.receiving);
        scope.manager("sup7_mgr_donor", s.donor);
        for (String name : ACCOUNTS) verified(name);
        Staff helper = s.donors.get(0);
        Mockito.clearInvocations(emailService);

        assertEquals(1, service.confirm(fx.plan(s.receiving, helper, s.tasks.get(0))).size());

        assertEquals(List.of(helper.getEmail(), "sup7_admin@scope.test", "sup7_mgr_recv@scope.test").stream().sorted().toList(), recipients());
        assertFalse(recipients().contains("sup7_mgr_donor@scope.test"), "the helper's home Manager is not the task's department Manager");
        assertFalse(recipients().contains("sup7_admin2@scope.test"), "an Admin who did not confirm is not told");
        // the confirming Admin is recorded as the original assigner
        UUIDCheck.assertAssigner(assignmentRepository, s.tasks.get(0).getId(), users.findByUsername("sup7_admin").orElseThrow().getId());
        assertTrue(recipients().stream().distinct().count() == recipients().size(), "no duplicates");
    }

    @Test
    @DisplayName("A support plan that is refused (rolled back) sends nothing and records no assignment")
    void refusedPlanSendsNothing() {
        var s = fx.overloaded(3, 3);
        scope.manager("sup7_mgr_recv", s.receiving);
        scope.manager("sup7_mgr_donor", s.donor);
        for (String name : ACCOUNTS) verified(name);
        var plan = fx.plan(s.receiving, s.donors.get(0), s.tasks.get(0));
        service.confirm(plan);
        Mockito.clearInvocations(emailService);

        // the same plan again: the task is already taken, so the whole request is refused
        org.junit.jupiter.api.Assertions.assertThrows(SupportPlanException.class, () -> service.confirm(plan));
        assertTrue(recipients().isEmpty(), "a refused plan emails nobody");
        assertEquals(1, assignmentRepository.findAllByTaskId(s.tasks.get(0).getId()).size());
    }

    /** Tiny helper kept local so the test reads top to bottom. */
    private static final class UUIDCheck {
        static void assertAssigner(AssignmentRepository repository, java.util.UUID taskId, java.util.UUID expected) {
            var rows = repository.findAllByTaskId(taskId);
            assertEquals(1, rows.size());
            assertEquals(expected, rows.get(0).getAssignedByUserId());
        }
    }
}
