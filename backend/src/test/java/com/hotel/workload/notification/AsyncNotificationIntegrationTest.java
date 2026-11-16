package com.hotel.workload.notification;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Application level, with the asynchronous notification worker ON (the production default; the rest of the suite runs the delivery inline):
 * a real HTTP request goes through the real security chain and a real transaction, its commit hands the notification to the worker, and
 * the worker reaches the (mocked) email service. SendGrid is never contacted.
 */
@SpringBootTest
@TestPropertySource(properties = "taskotel.notifications.async=true")
public class AsyncNotificationIntegrationTest {

    private static final String DEPT = "AI_DEPT";
    private static final String[] USERS = {"ai_admin", "ai_staff"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department department;
    private Staff staff;
    private User admin;
    private final List<String> senderThreads = new CopyOnWriteArrayList<>();
    private final List<String> recipients = new CopyOnWriteArrayList<>();
    private final List<String> subjects = new CopyOnWriteArrayList<>();
    private CountDownLatch reached;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        department = fixtures.department(DEPT);
        admin = fixtures.account("ai_admin", Role.ADMIN, true);
        staff = fixtures.staff("ai_staff", department);
        User account = staff.getUser();
        account.setEmailVerified(true); // every notification goes only to a verified address
        userRepository.saveAndFlush(account);

        reached = new CountDownLatch(1);
        recipients.clear();
        subjects.clear();
        senderThreads.clear();
        when(emailService.isConfigured()).thenReturn(true);
    }

    @AfterEach
    public void cleanUp() throws Exception {
        Thread.sleep(100); // let a worker that is still finishing the current event finish before the rows disappear
        taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && DEPT.equals(t.getDepartment().getCode())).forEach(t -> {
            assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
            assignmentRepository.flush();
            taskRepository.delete(t);
        });
        taskRepository.flush();
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        fixtures.remove(USERS);
        departmentRepository.findByCode(DEPT).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    private Task newTask() {
        Task task = new Task();
        task.setTitle("Async integration task");
        task.setPriority(Priority.HIGH);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(45);
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private MvcResult assign(Task task) throws Exception {
        String body = "{\"taskId\":\"" + task.getId() + "\",\"staffId\":\"" + staff.getId() + "\"}";
        return mockMvc.perform(post("/api/assignments").with(user("ai_admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** Records every call to the mocked SendGrid-facing service, with the thread that made it, and signals the test. */
    private void recordCalls(boolean failWithEmailError) {
        doAnswer(invocation -> {
            senderThreads.add(Thread.currentThread().getName());
            recipients.add(invocation.getArgument(0));
            subjects.add(invocation.getArgument(1));
            reached.countDown();
            if (failWithEmailError) throw new EmailSendingException("SendGrid is down");
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A real assignment request commits, and its notification then reaches the mocked email service through the notify- worker, not the request thread")
    public void requestNotificationReachesTheWorker() throws Exception {
        recordCalls(false);
        Task task = newTask();

        MvcResult result = assign(task);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(reached.await(15, TimeUnit.SECONDS), "the worker never reached the email service");

        assertEquals(List.of(staff.getUser().getEmail()), recipients, "exactly one email, to the assignee");
        assertEquals("Taskotel - New task assigned: Async integration task", subjects.get(0));
        assertEquals(1, senderThreads.size());
        assertTrue(senderThreads.get(0).startsWith("notify-"), "sent by the worker: " + senderThreads.get(0));
        assertNotEquals(Thread.currentThread().getName(), senderThreads.get(0), "not on the request thread");
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("A failing email service never undoes the business operation: the request still succeeds and the assignment is committed, although the worker did reach the email service")
    public void emailFailureDoesNotUndoTheOperation() throws Exception {
        recordCalls(true);
        Task task = newTask();

        MvcResult result = assign(task);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(reached.await(15, TimeUnit.SECONDS), "the worker never reached the email service");

        assertTrue(assignmentRepository.findAll().stream()
                .anyMatch(a -> a.getTask().getId().equals(task.getId()) && a.getStaff().getId().equals(staff.getId())), "the assignment is committed");
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        assertTrue(senderThreads.get(0).startsWith("notify-"));

        // the worker survived the failure: a later event is still delivered
        Mockito.reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        reached = new CountDownLatch(1);
        recordCalls(false);
        Task second = newTask();
        // the staff member holds only one active task at a time, so finish the first one directly in the database (no notification) and assign the second
        Task first = taskRepository.findById(task.getId()).orElseThrow();
        first.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(first);
        MvcResult again = assign(second);
        assertEquals(201, again.getResponse().getStatus(), again.getResponse().getContentAsString());
        assertTrue(reached.await(15, TimeUnit.SECONDS), "the worker still delivers after an earlier failure");
    }
}
