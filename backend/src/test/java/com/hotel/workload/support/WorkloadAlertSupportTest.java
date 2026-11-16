package com.hotel.workload.support;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.WorkloadAlertState;
import com.hotel.workload.notification.WorkloadAlertStateRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportDtos.SupportAssignmentDto;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * N4 and Cross-Department Assistance: confirming a support plan raises the HELPER's global workload, so the helper is told, while the
 * Managers of the helper's home (donor) department and of the receiving department hear nothing and nothing about the loan work leaves the
 * Staff member's own email. Ending the support by hand is a recovery (no email), and a later loan alerts again. Real commits, mocked email.
 */
@SpringBootTest
public class WorkloadAlertSupportTest {

    @Autowired private SupportAssignmentService service;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private WorkloadAlertStateRepository stateRepository;
    @MockitoBean private EmailService emailService;

    private SupportTestFixture fx;
    private SupportTestFixture.Scenario s;
    private Staff helper;
    private Staff donorManager;
    private Staff receivingManager;

    @BeforeEach
    void setUp() {
        when(emailService.isConfigured()).thenReturn(true);
        fx = new SupportTestFixture(users, staffRepository, taskRepository, assignmentRepository, departmentRepository, supportRepository);
        SupportTestFixture.signInAs("support_actor_not_a_user", "ADMIN");
        s = fx.overloaded(1, 3);
        helper = s.donors.get(0);
        donorManager = fx.person("Donor Manager", s.donor, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        receivingManager = fx.person("Receiving Manager", s.receiving, Role.MANAGER, true, false, true, Availability.AVAILABLE, true);
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    void tearDown() {
        SupportTestFixture.signOut();
        stateRepository.deleteAll(stateRepository.findAll().stream().filter(r -> r.getStaffId().equals(helper.getId())).toList());
        fx.cleanUp();
    }

    private List<String> alertRecipients() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .filter(i -> ((String) i.getArgument(1)).startsWith("Taskotel - Your workload is now") || ((String) i.getArgument(1)).startsWith("Taskotel - Workload alert:"))
                .map(i -> (String) i.getArgument(0)).sorted().collect(Collectors.toList());
    }

    private String[] alertTo(String address) {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail") && i.getArgument(0).equals(address))
                .filter(i -> ((String) i.getArgument(1)).startsWith("Taskotel - Your workload is now"))
                .map(i -> new String[] {i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)}).findFirst().orElseThrow();
    }

    private String level(String audience) {
        return stateRepository.findByStaffIdAndAudience(helper.getId(), audience).map(WorkloadAlertState::getLevel).orElse("(none)");
    }

    @Test
    @DisplayName("Support confirmation alerts the helper only (global level); manual end recovers silently; a later loan alerts again")
    void confirmAndEnd() {
        Task loan = fx.waiting("Confidential loan task", s.receiving, Priority.URGENT, 300);
        SupportAssignmentDto support = service.confirm(fx.plan(s.receiving, helper, loan)).get(0);

        assertEquals(List.of(helper.getUser().getEmail()), alertRecipients(),
                "only the helper: the donor department's Manager sees a home-department level that loan work does not touch, and the receiving Manager is not authorised for the donor Staff member");
        String text = alertTo(helper.getUser().getEmail())[2] + alertTo(helper.getUser().getEmail())[3];
        assertTrue(text.contains("300 minutes") && text.contains("Active tasks: 1"));
        assertFalse(text.contains("Confidential loan task") || text.contains(s.receiving.getName()), "no task title and no receiving department");
        assertEquals("HIGH", level("STAFF"));
        assertEquals("LOW", level("MANAGER"), "the home-department view does not count the loan task");
        assertFalse(alertRecipients().contains(donorManager.getUser().getEmail()));
        assertFalse(alertRecipients().contains(receivingManager.getUser().getEmail()));

        Mockito.clearInvocations(emailService);
        service.end(support.id());
        assertTrue(alertRecipients().isEmpty(), "ending the support is a recovery: no email");
        assertEquals("LOW", level("STAFF"));

        Task second = fx.waiting("Second loan task", s.receiving, Priority.URGENT, 300);
        service.confirm(fx.plan(s.receiving, helper, second));
        assertEquals(List.of(helper.getUser().getEmail()), alertRecipients(), "rising into HIGH again alerts again");
    }
}
