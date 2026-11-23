package com.hotel.workload.notification;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.TaskReminderService.RunResult;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.ManagerViewGrant;
import com.hotel.workload.staff.ManagerViewGrantRepository;
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
import org.springframework.context.ApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Task reminders end to end, in a real Spring context with real transactions and a mocked email provider (nothing is ever sent). The moment
 * of every run is passed in, so each scenario states exactly when the scheduler "ran" (Asia/Kolkata).
 *
 * <p>World: department HOME with Manager {@code n8_mgr}; {@code n8_mgr_grant} (primary department OTHER) holds an active grant for HOME;
 * {@code n8_mgr_other} manages OTHER only; Staff {@code n8_s1}, {@code n8_s2} in HOME. Everyone has TASK_REMINDER switched on unless a test
 * says otherwise (an account created through the application starts with every optional category on).
 */
@SpringBootTest
public class TaskReminderServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DUE = LocalDate.of(2026, 12, 10);
    private static final String HOME = "N8_HOME";
    private static final String OTHER = "N8_OTHER";
    private static final String[] USERS = {"n8_admin", "n8_mgr", "n8_mgr_grant", "n8_mgr_other", "n8_s1", "n8_s2"};

    @Autowired private ApplicationContext context;
    @MockitoSpyBean private TaskReminderService service;
    @Autowired private TaskReminderLogRepository logRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerViewGrantRepository grantRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;
    @MockitoSpyBean private NotificationService notificationService;

    private ScopeFixtures fixtures;
    private Department home;
    private Department other;
    private User admin;
    private Staff mgr;
    private Staff s1;
    private Staff s2;
    private final List<UUID> taskIds = new ArrayList<>();

    /**
     * The worker reads the CURRENT time right before sending, so these tests (which run the scheduler at made-up moments) give the service a
     * simulated clock: it reads as the moment of the latest run, unless a test moves it on purpose to simulate a backed-up queue.
     */
    private final java.util.concurrent.atomic.AtomicReference<ZonedDateTime> simulatedNow = new java.util.concurrent.atomic.AtomicReference<>();

    private void installSimulatedClock() {
        doAnswer(invocation -> {
            simulatedNow.set(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(service).run(any());
        doAnswer(invocation -> simulatedNow.get()).when(service).deliveryNow();
    }

    @BeforeEach
    public void setUp() {
        installSimulatedClock();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        other = fixtures.department(OTHER);
        admin = fixtures.account("n8_admin", Role.ADMIN, true);
        mgr = fixtures.manager("n8_mgr", home);
        Staff mgrGrant = fixtures.manager("n8_mgr_grant", other);
        fixtures.manager("n8_mgr_other", other);
        s1 = fixtures.staff("n8_s1", home);
        s2 = fixtures.staff("n8_s2", home);
        ManagerViewGrant grant = new ManagerViewGrant();
        grant.setManager(mgrGrant.getUser());
        grant.setDepartment(home);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername("n8_admin");
        grantRepository.saveAndFlush(grant);
        for (String name : USERS) {
            User account = userRepository.findByUsername(name).orElseThrow();
            account.setEmailVerified(true);
            userRepository.saveAndFlush(account);
            preference(name, NotificationCategory.TASK_REMINDER, true);
        }
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() {
        logRepository.deleteAll(logRepository.findAll().stream().filter(l -> taskIds.contains(l.getTaskId())).toList());
        for (UUID id : taskIds) {
            assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(id));
            assignmentRepository.flush();
            taskRepository.findById(id).ifPresent(taskRepository::delete);
        }
        taskRepository.flush();
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> {
                grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(u.getId())).forEach(grantRepository::delete);
                preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId()));
            });
        }
        grantRepository.flush();
        fixtures.remove(USERS);
        for (String code : List.of(HOME, OTHER)) departmentRepository.findByCode(code).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
        taskIds.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void preference(String username, NotificationCategory category, boolean email) {
        User account = userRepository.findByUsername(username).orElseThrow();
        NotificationPreference existing = preferenceRepository.findByUserId(account.getId()).stream()
                .filter(row -> category.name().equals(row.getCategory())).findFirst().orElse(null);
        if (existing == null) {
            preferenceRepository.saveAndFlush(new NotificationPreference(account.getId(), category, true, email));
        } else {
            existing.setEmail(email);
            preferenceRepository.saveAndFlush(existing);
        }
    }

    private static ZonedDateTime ist(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, IST);
    }

    private Task task(String title, LocalTime deadline, TaskStatus status, Staff... assignees) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.HIGH);
        task.setStatus(status);
        task.setDueDate(DUE);
        task.setDeadlineTime(deadline);
        task.setDepartment(home);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        taskIds.add(task.getId());
        for (Staff assignee : assignees) assign(task, assignee);
        return task;
    }

    /** Assigned long ago (before any reminder moment these tests use), so the person was there for every reminder. */
    private void assign(Task task, Staff staff) {
        assignAt(task, staff, LocalDateTime.of(2026, 1, 1, 0, 0).atZone(java.time.ZoneId.systemDefault()));
    }

    /** Assigned at an exact moment ({@code assignedAt} is stored in the server's zone, as the application writes it). */
    private void assignAt(Task task, Staff staff, ZonedDateTime when) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignment = assignmentRepository.saveAndFlush(assignment);
        assignment.setAssignedAt(when.withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime());
        assignmentRepository.saveAndFlush(assignment);
    }

    private List<String[]> sent() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail"))
                .map(i -> new String[] {i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)})
                .filter(m -> m[0].toLowerCase().startsWith("n8_"))
                .collect(Collectors.toList());
    }

    private List<String> recipients() {
        List<String> to = new ArrayList<>();
        sent().forEach(m -> to.add(m[0]));
        to.sort(String::compareTo);
        return to;
    }

    private static List<String> mails(String... usernames) {
        return java.util.Arrays.stream(usernames).map(u -> u + "@scope.test").sorted().toList();
    }

    private String[] mailTo(String username) {
        return sent().stream().filter(m -> m[0].equals(username + "@scope.test")).findFirst().orElseThrow();
    }

    private List<TaskReminderLog> rows(Task task) {
        return logRepository.findByTaskId(task.getId());
    }

    private UUID userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private TaskReminderLog row(Task task, String username) {
        return rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId(username))).findFirst().orElseThrow();
    }

    private void reset() {
        Mockito.clearInvocations(emailService);
    }

    // ── due soon ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2 hours before a deadline time: the assignee is reminded (and nobody else); the log says SENT only after the email was accepted")
    public void twoHourReminderGoesToTheAssignee() {
        Task task = task("Deep clean 402", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        RunResult result = service.run(ist(12, 10, 12, 0));
        assertEquals(1, result.queued());
        assertEquals(mails("n8_s1"), recipients(), "not the Manager of an assigned task, not the other Staff");
        String[] mail = mailTo("n8_s1");
        assertEquals("Taskotel - Reminder: Deep clean 402 - Due in 2 hours", mail[1]);
        assertTrue(mail[2].contains("Deep clean 402") && mail[2].contains("2026-12-10 14:00 IST"), mail[2]);
        TaskReminderLog row = row(task, "n8_s1");
        assertEquals(ReminderStatus.SENT, row.getStatus());
        assertEquals(ReminderKind.DUE_2H, row.getKind());
        assertEquals("2026-12-10T14:00", row.getDueKey());
        assertTrue(row.getFinishedAt() != null);
    }

    @Test
    @DisplayName("24 hours before a deadline time: the assignee is reminded; a UTC instant is read as the same moment in IST")
    public void twentyFourHourReminderFromUtcInstant() {
        task("Linen", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        // 08:30 UTC on the 9th is 14:00 IST on the 9th: 24 hours before 14:00 IST on the 10th
        service.run(ZonedDateTime.of(2026, 12, 9, 8, 30, 0, 0, ZoneOffset.UTC));
        assertEquals(mails("n8_s1"), recipients());
        assertTrue(mailTo("n8_s1")[1].endsWith("Due in 24 hours"));
    }

    @Test
    @DisplayName("A date-only task: the due-today reminder is at 09:00 IST on the due date, to the assignee")
    public void dueTodayForDateOnlyTask() {
        task("Date only", null, TaskStatus.ASSIGNED, s1);
        assertEquals(0, service.run(ist(12, 10, 8, 45)).due(), "before 09:00");
        service.run(ist(12, 10, 9, 0));
        assertEquals(mails("n8_s1"), recipients());
        assertTrue(mailTo("n8_s1")[1].endsWith("Due today"));
    }

    @Test
    @DisplayName("An unassigned PENDING task: the department Managers are reminded (primary and granted), not Staff and not an unrelated Manager")
    public void unassignedTaskRemindsManagersOnly() {
        task("Nobody yet", LocalTime.of(14, 0), TaskStatus.PENDING);
        service.run(ist(12, 10, 12, 0));
        assertEquals(mails("n8_mgr", "n8_mgr_grant"), recipients());
        assertTrue(mailTo("n8_mgr")[2].contains("not assigned to anyone yet"), mailTo("n8_mgr")[2]);
        assertFalse(recipients().contains("n8_mgr_other@scope.test"), "a Manager of another department is never reminded");
    }

    // ── suppression ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Repeated runs inside the window never repeat a reminder")
    public void repeatedRunsDoNotRepeat() {
        task("Once", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        RunResult first = service.run(ist(12, 10, 12, 0));
        assertEquals(1, first.claimed());
        RunResult second = service.run(ist(12, 10, 12, 0));
        RunResult third = service.run(ist(12, 10, 12, 15));
        assertEquals(0, second.claimed());
        assertEquals(1, second.alreadyClaimed());
        assertEquals(1, third.alreadyClaimed());
        assertEquals(1, sent().size(), "one email in total");
    }

    @Test
    @DisplayName("Overdue: first at the deadline, then daily at 09:00, at most THREE emails in total per task and person")
    public void overdueIsCappedAtThree() {
        Task task = task("Late", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        service.run(ist(12, 10, 10, 0));
        service.run(ist(12, 11, 9, 0));
        service.run(ist(12, 12, 9, 0));
        RunResult fourth = service.run(ist(12, 13, 9, 0));
        long toS1 = sent().stream().filter(m -> m[0].equals("n8_s1@scope.test")).count();
        assertEquals(3, toS1, "the first overdue email counts: three in total");
        assertEquals(3, fourth.capped() , "s1, the Manager and the granted Manager are each capped (the Managers also had three)");
        assertEquals(0, fourth.queued());
        assertEquals(3, rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId("n8_s1"))).count());
    }

    @Test
    @DisplayName("Overdue on an assigned task goes to the assignee AND the department Managers; one email each")
    public void overdueRecipients() {
        task("Overdue job", LocalTime.of(10, 0), TaskStatus.IN_PROGRESS, s1);
        service.run(ist(12, 10, 10, 0));
        assertEquals(mails("n8_s1", "n8_mgr", "n8_mgr_grant"), recipients());
        assertFalse(recipients().contains("n8_s2@scope.test"));
        assertFalse(recipients().contains("n8_mgr_other@scope.test"));
    }

    @Test
    @DisplayName("Several assignees each get their own reminder; each Manager gets ONE email for the task")
    public void multipleAssignees() {
        task("Team job", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1, s2);
        service.run(ist(12, 10, 10, 0));
        assertEquals(mails("n8_s1", "n8_s2", "n8_mgr", "n8_mgr_grant"), recipients());
    }

    @Test
    @DisplayName("A person with several tasks due in one run gets ONE email listing them")
    public void digestForSeveralTasks() {
        task("Alpha", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        // a second task for the same person: it needs a different assignee row, so give it to s1 too (the unique key is per task)
        task("Beta", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        service.run(ist(12, 10, 12, 0));
        assertEquals(mails("n8_s1"), recipients());
        String[] mail = mailTo("n8_s1");
        assertEquals("Taskotel - Reminder: 2 tasks need your attention", mail[1]);
        assertTrue(mail[2].contains("Alpha") && mail[2].contains("Beta"), mail[2]);
    }

    @Test
    @DisplayName("Changing the due time starts a fresh set: the new deadline is reminded again")
    public void changedDueDateRearms() {
        Task task = task("Moved", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        service.run(ist(12, 10, 12, 0));
        assertEquals(1, sent().size());
        Task edited = taskRepository.findById(task.getId()).orElseThrow();
        edited.setDeadlineTime(LocalTime.of(16, 0));
        taskRepository.saveAndFlush(edited);
        service.run(ist(12, 10, 14, 0));
        assertEquals(2, sent().size(), "the 2-hour reminder for the new 16:00 deadline");
        assertEquals(2, rows(task).size());
    }

    // ── statuses, reassignment, late assignment ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Completed and cancelled tasks are never reminded")
    public void finishedTasksAreNeverReminded() {
        task("Done", LocalTime.of(14, 0), TaskStatus.COMPLETED, s1);
        task("Cancelled", LocalTime.of(14, 0), TaskStatus.CANCELLED);
        assertEquals(0, service.run(ist(12, 10, 12, 0)).due());
        assertEquals(0, service.run(ist(12, 10, 14, 0)).due());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("A deleted task is never reminded and leaves nothing behind that could")
    public void deletedTaskIsNeverReminded() {
        Task task = task("Deleted", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(task.getId()));
        taskRepository.deleteById(task.getId());
        taskRepository.flush();
        assertEquals(0, service.run(ist(12, 10, 12, 0)).due());
        assertTrue(sent().isEmpty());
    }

    @Test
    @DisplayName("A task finished AFTER the reminder was claimed but BEFORE it was sent is not reminded about, and the log says SKIPPED")
    public void taskFinishedBetweenClaimAndDelivery() {
        Task task = task("Race", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doAnswer(invocation -> {
            Task live = taskRepository.findById(task.getId()).orElseThrow();
            live.setStatus(TaskStatus.COMPLETED);
            taskRepository.saveAndFlush(live);
            return invocation.callRealMethod();
        }).when(notificationService).enqueueTracked(any(), any(), any(), any());
        service.run(ist(12, 10, 12, 0));
        assertTrue(sent().isEmpty(), "the task finished before delivery");
        assertEquals(ReminderStatus.SKIPPED, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("A reassigned task reminds the new assignee and no longer the previous one")
    public void reassignedTask() {
        Task task = task("Moved on", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        Assignment assignment = assignmentRepository.findAllByTaskId(task.getId()).get(0);
        assignment.setStaff(s2);
        assignmentRepository.saveAndFlush(assignment);
        service.run(ist(12, 10, 12, 0));
        assertEquals(mails("n8_s2"), recipients());
    }

    @Test
    @DisplayName("A task becomes overdue before anyone is assigned: the Managers hear first, the late assignee gets the NEXT reminder and none of the missed ones")
    public void lateAssignmentReceivesOnlyTheNextReminder() {
        Task task = task("Late assignee", LocalTime.of(10, 0), TaskStatus.PENDING);
        service.run(ist(12, 10, 10, 0));                      // overdue slot 0: unassigned, so the Managers
        assertEquals(mails("n8_mgr", "n8_mgr_grant"), recipients());
        reset();

        assignAt(task, s1, ist(12, 10, 10, 5));               // assigned 5 minutes AFTER the first overdue moment
        Task live = taskRepository.findById(task.getId()).orElseThrow();
        live.setStatus(TaskStatus.ASSIGNED);
        taskRepository.saveAndFlush(live);
        service.run(ist(12, 10, 10, 15));
        service.run(ist(12, 10, 12, 0));
        service.run(ist(12, 11, 8, 45));
        assertTrue(sent().isEmpty(), "nothing is replayed for the assignee, not even inside the first slot's grace window: they were assigned after it");

        service.run(ist(12, 11, 9, 0));                       // overdue slot 1: the assignee's first email, plus the Managers'
        assertEquals(mails("n8_s1", "n8_mgr", "n8_mgr_grant"), recipients());
        assertEquals(1, sent().stream().filter(m -> m[0].equals("n8_s1@scope.test")).count());
        assertEquals(1, rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId("n8_s1"))).count(), "only the current slot was logged for them");
    }

    @Test
    @DisplayName("Someone assigned long after the deadline starts their own count of three at the current daily slot")
    public void veryLateAssigneeHasOwnCap() {
        Task task = task("Ancient", LocalTime.of(10, 0), TaskStatus.PENDING);
        assignAt(task, s1, ist(12, 16, 12, 0));              // six days after the deadline
        Task live = taskRepository.findById(task.getId()).orElseThrow();
        live.setStatus(TaskStatus.ASSIGNED);
        taskRepository.saveAndFlush(live);
        for (int day = 17; day <= 20; day++) service.run(ist(12, day, 9, 0));   // slots 7..10
        long toS1 = sent().stream().filter(m -> m[0].equals("n8_s1@scope.test")).count();
        assertEquals(3, toS1, "three emails, starting at the first slot after they were assigned");
    }

    // ── eligibility ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Each recipient's TASK_REMINDER preference decides; the log records SKIPPED for the one who opted out")
    public void preferenceOff() {
        preference("n8_s1", NotificationCategory.TASK_REMINDER, false);
        preference("n8_mgr", NotificationCategory.TASK_REMINDER, false);
        Task task = task("Prefs", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        service.run(ist(12, 10, 10, 0));
        assertEquals(mails("n8_mgr_grant"), recipients());
        assertEquals(ReminderStatus.SKIPPED, row(task, "n8_s1").getStatus());
        assertEquals(ReminderStatus.SENT, row(task, "n8_mgr_grant").getStatus());
    }

    @Test
    @DisplayName("An inactive Manager, an inactive assignee and a Manager whose grant was revoked are not reminded")
    public void ineligibleRecipients() {
        User inactiveManager = mgr.getUser();
        inactiveManager.setActive(false);
        userRepository.saveAndFlush(inactiveManager);
        User inactiveStaff = s1.getUser();
        inactiveStaff.setActive(false);
        userRepository.saveAndFlush(inactiveStaff);
        grantRepository.deleteAll(grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(userId("n8_mgr_grant"))).toList());
        grantRepository.flush();
        Task task = task("Nobody eligible", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1, s2);
        service.run(ist(12, 10, 10, 0));
        assertEquals(mails("n8_s2"), recipients(), "only the active assignee; no Manager of HOME is left");
        assertEquals(ReminderStatus.SKIPPED, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("Two accounts sharing one address get one email between them")
    public void sharedAddressGetsOneEmail() {
        User twin = userRepository.findByUsername("n8_mgr_grant").orElseThrow();
        twin.setEmail(userRepository.findByUsername("n8_mgr").orElseThrow().getEmail().toUpperCase());
        userRepository.saveAndFlush(twin);
        task("Twins", LocalTime.of(14, 0), TaskStatus.PENDING);
        service.run(ist(12, 10, 12, 0));
        assertEquals(1, sent().stream().filter(m -> m[0].equalsIgnoreCase("n8_mgr@scope.test")).count());
    }

    @Test
    @DisplayName("When email is not configured nothing is claimed or sent, and the reminders stay available for a later run")
    public void emailNotConfigured() {
        Task task = task("No provider", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        when(emailService.isConfigured()).thenReturn(false);
        RunResult result = service.run(ist(12, 10, 12, 0));
        assertEquals(0, result.claimed());
        assertTrue(rows(task).isEmpty());
        when(emailService.isConfigured()).thenReturn(true);
        service.run(ist(12, 10, 12, 15));
        assertEquals(mails("n8_s1"), recipients(), "still inside the window once email works");
    }

    // ── the queue and the provider ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The queue REJECTS the job: nothing is sent, no claim is left behind (not marked delivered), and the next run inside the window delivers")
    public void queueRejectionReleasesTheClaim() {
        Task task = task("Rejected", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doReturn(false).when(notificationService).enqueueTracked(any(), any(), any(), any());
        RunResult rejected = service.run(ist(12, 10, 12, 0));
        assertEquals(1, rejected.claimed());
        assertEquals(0, rejected.queued());
        assertEquals(1, rejected.rejected());
        assertTrue(sent().isEmpty());
        assertTrue(rows(task).isEmpty(), "a reminder the queue refused is not recorded as claimed, let alone sent");

        Mockito.reset(notificationService);                  // the queue accepts again
        RunResult retry = service.run(ist(12, 10, 12, 15));
        assertEquals(1, retry.queued());
        assertEquals(mails("n8_s1"), recipients());
        assertEquals(ReminderStatus.SENT, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("A rejected reminder whose window has closed is dropped, not sent late")
    public void rejectedReminderIsNotSentLate() {
        Task task = task("Too late", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doReturn(false).when(notificationService).enqueueTracked(any(), any(), any(), any());
        service.run(ist(12, 10, 12, 0));
        Mockito.reset(notificationService);
        RunResult late = service.run(ist(12, 10, 12, 30));
        assertEquals(0, late.due());
        assertTrue(sent().isEmpty());
        assertTrue(rows(task).isEmpty());
    }

    @Test
    @DisplayName("SendGrid fails: the run is unaffected, the log says FAILED, and the reminder is not retried")
    public void sendGridFailure() {
        Task task = task("Provider down", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doThrow(new EmailSendingException("SendGrid is down")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        RunResult result = service.run(ist(12, 10, 12, 0));
        assertEquals(1, result.queued());
        assertEquals(ReminderStatus.FAILED, row(task, "n8_s1").getStatus());
        Mockito.clearInvocations(emailService);
        doAnswer(i -> null).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        RunResult next = service.run(ist(12, 10, 12, 15));
        assertEquals(1, next.alreadyClaimed());
        assertTrue(sent().isEmpty(), "FAILED is final for that reminder: at most once");
    }

    @Test
    @DisplayName("A job that was accepted but never reported back is not trusted: after an hour its CLAIMED row becomes UNKNOWN (possibly sent, never FAILED), and it is not retried")
    public void staleClaimBecomesUnknown() {
        Task task = task("Crashed", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        TaskReminderLog stuck = new TaskReminderLog(task.getId(), userId("n8_s1"), ReminderKind.DUE_2H, 0, "2026-12-10T14:00", LocalDateTime.of(2026, 12, 10, 10, 0));
        logRepository.saveAndFlush(stuck);
        service.run(ist(12, 10, 12, 0));
        assertEquals(ReminderStatus.UNKNOWN, logRepository.findById(stuck.getId()).orElseThrow().getStatus());
        assertTrue(sent().isEmpty(), "the claim blocks a repeat; the unknown outcome is not guessed to be unsent");
    }

    @Test
    @DisplayName("Concurrent runs (two schedulers or instances) at the same moment send each reminder exactly once")
    public void concurrentRunsSendOnce() throws Exception {
        Task task = task("Contended", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1, s2);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RunResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.run(ist(12, 10, 10, 0));
            }));
        }
        ready.await();
        go.countDown();
        int claimed = 0;
        for (Future<RunResult> f : futures) claimed += f.get(60, TimeUnit.SECONDS).claimed();
        pool.shutdown();
        assertEquals(4, claimed, "four reminders exist (two assignees, two Managers) and each was claimed by exactly one run");
        assertEquals(mails("n8_s1", "n8_s2", "n8_mgr", "n8_mgr_grant"), recipients(), "each recipient got exactly one email");
        assertEquals(4, rows(task).size());
        assertTrue(rows(task).stream().allMatch(r -> r.getStatus() == ReminderStatus.SENT));
    }

    // ── shared email addresses ───────────────────────────────────────────────────────────────────────────────────

    /** s1 (assignee) and the Manager n8_mgr read the same mailbox. Alpha is held by s1; Beta is unassigned, so only the Managers hear of it. */
    private Task[] sharedMailboxWorld() {
        User s1User = userRepository.findByUsername("n8_s1").orElseThrow();
        s1User.setEmail(userRepository.findByUsername("n8_mgr").orElseThrow().getEmail().toUpperCase());   // same mailbox, written in another letter case (the column is unique per exact spelling)
        userRepository.saveAndFlush(s1User);
        Task alpha = task("Alpha job", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        Task beta = task("Beta unassigned", LocalTime.of(10, 0), TaskStatus.PENDING);
        return new Task[] {alpha, beta};
    }

    private List<String[]> mailsTo(String address) {
        return sent().stream().filter(m -> m[0].equalsIgnoreCase(address)).toList();
    }

    private static final String SHARED = "n8_mgr@scope.test";

    @Test
    @DisplayName("Shared address, DIFFERENT reminder content: one email per address that combines everyone's reminders; every account's row is SENT")
    public void sharedAddressCombinesDifferentContent() {
        Task[] t = sharedMailboxWorld();
        service.run(ist(12, 10, 10, 0));
        List<String[]> shared = mailsTo(SHARED);
        assertEquals(1, shared.size(), "one email to the shared mailbox, not two and not one that drops somebody's tasks");
        assertTrue(shared.get(0)[2].contains("Alpha job") && shared.get(0)[2].contains("Beta unassigned"), shared.get(0)[2]);
        assertEquals(1, shared.get(0)[2].split("Alpha job", -1).length - 1, "the task both accounts were reminded of appears once");
        assertEquals(ReminderStatus.SENT, row(t[0], "n8_s1").getStatus(), "s1 is not SKIPPED just because the Manager shares the address");
        assertEquals(ReminderStatus.SENT, row(t[0], "n8_mgr").getStatus());
        assertEquals(ReminderStatus.SENT, row(t[1], "n8_mgr").getStatus());
        assertEquals(1, mailsTo("n8_mgr_grant@scope.test").size(), "the granted Manager has an address of their own");
        assertTrue(rows(t[0]).stream().noneMatch(r -> r.getStatus() == ReminderStatus.SKIPPED));
    }

    @Test
    @DisplayName("Shared address, assignee opted out: the Manager's email is unchanged and the assignee's row is SKIPPED (because of the preference, nothing else)")
    public void sharedAddressAssigneeOptedOut() {
        preference("n8_s1", NotificationCategory.TASK_REMINDER, false);
        Task[] t = sharedMailboxWorld();
        service.run(ist(12, 10, 10, 0));
        List<String[]> shared = mailsTo(SHARED);
        assertEquals(1, shared.size());
        assertTrue(shared.get(0)[2].contains("Alpha job") && shared.get(0)[2].contains("Beta unassigned"), "the Manager still hears about both tasks");
        assertEquals(ReminderStatus.SKIPPED, row(t[0], "n8_s1").getStatus());
        assertEquals(ReminderStatus.SENT, row(t[0], "n8_mgr").getStatus());
        assertEquals(ReminderStatus.SENT, row(t[1], "n8_mgr").getStatus());
    }

    @Test
    @DisplayName("Shared address, Manager opted out: the email carries ONLY the assignee's reminder (the opted-out account's tasks are not leaked), the Manager's rows are SKIPPED")
    public void sharedAddressManagerOptedOut() {
        preference("n8_mgr", NotificationCategory.TASK_REMINDER, false);
        Task[] t = sharedMailboxWorld();
        service.run(ist(12, 10, 10, 0));
        List<String[]> shared = mailsTo(SHARED);
        assertEquals(1, shared.size());
        assertTrue(shared.get(0)[2].contains("Alpha job"));
        assertFalse(shared.get(0)[2].contains("Beta unassigned"), "Beta was only the opted-out Manager's reminder");
        assertEquals(ReminderStatus.SENT, row(t[0], "n8_s1").getStatus());
        assertEquals(ReminderStatus.SKIPPED, row(t[0], "n8_mgr").getStatus());
        assertEquals(ReminderStatus.SKIPPED, row(t[1], "n8_mgr").getStatus());
    }

    @Test
    @DisplayName("Shared address, the provider CONFIRMS a refusal: every account behind that address is FAILED (and not counted as sent)")
    public void sharedAddressConfirmedFailure() {
        Task[] t = sharedMailboxWorld();
        doThrow(new EmailSendingException("SendGrid said 400")).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        service.run(ist(12, 10, 10, 0));
        assertEquals(ReminderStatus.FAILED, row(t[0], "n8_s1").getStatus());
        assertEquals(ReminderStatus.FAILED, row(t[0], "n8_mgr").getStatus());
        assertEquals(ReminderStatus.FAILED, row(t[1], "n8_mgr").getStatus());
    }

    @Test
    @DisplayName("Shared address, the connection breaks after the request may have been accepted: every account behind it is UNKNOWN, not FAILED")
    public void sharedAddressUnknownOutcome() {
        Task[] t = sharedMailboxWorld();
        doThrow(new EmailSendingException("Failed to dispatch", new IOException("timed out"))).when(emailService)
                .sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        service.run(ist(12, 10, 10, 0));
        assertEquals(ReminderStatus.UNKNOWN, row(t[0], "n8_s1").getStatus());
        assertEquals(ReminderStatus.UNKNOWN, row(t[0], "n8_mgr").getStatus());
        assertEquals(ReminderStatus.UNKNOWN, row(t[1], "n8_mgr").getStatus());
    }

    // ── delivery-time freshness ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A BACKED-UP queue: claimed at 12:00 but the worker reaches it at 12:45, after the 30-minute window: nothing is sent, the row says EXPIRED, and it does not count toward the cap")
    public void backedUpQueueExpiresTheReminder() {
        Task task = task("Backlogged", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doReturn(ist(12, 10, 12, 45)).when(service).deliveryNow();
        RunResult result = service.run(ist(12, 10, 12, 0));
        assertEquals(1, result.claimed());
        assertTrue(sent().isEmpty(), "never sent late");
        TaskReminderLog row = row(task, "n8_s1");
        assertEquals(ReminderStatus.EXPIRED, row.getStatus());
        assertEquals(LocalDateTime.of(2026, 12, 10, 12, 45), row.getFinishedAt(), "the outcome is stamped with the delivery time, not the claim time");
    }

    @Test
    @DisplayName("The worker reaches the reminder just inside its window (12:29): it is still sent")
    public void justInsideTheWindowIsStillSent() {
        Task task = task("Just in time", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doReturn(ist(12, 10, 12, 29)).when(service).deliveryNow();
        service.run(ist(12, 10, 12, 0));
        assertEquals(mails("n8_s1"), recipients());
        assertEquals(ReminderStatus.SENT, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("A due-soon reminder that is delivered AFTER the deadline has passed is expired, not sent as 'due in 2 hours'")
    public void dueSoonDeliveredAfterTheDeadlineIsExpired() {
        Task task = task("Missed it", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doReturn(ist(12, 10, 14, 5)).when(service).deliveryNow();   // the task is now overdue
        service.run(ist(12, 10, 12, 0));
        assertTrue(sent().isEmpty());
        assertEquals(ReminderStatus.EXPIRED, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("A stale OVERDUE reminder (delivered after its slot's window) is expired and does not count toward the cap, so the next daily slot is still sent")
    public void expiredOverdueDoesNotUseTheCap() {
        Task task = task("Stale overdue", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        doReturn(ist(12, 10, 10, 31)).when(service).deliveryNow();
        service.run(ist(12, 10, 10, 0));
        assertTrue(sent().isEmpty());
        assertEquals(ReminderStatus.EXPIRED, row(task, "n8_s1").getStatus());
        Mockito.reset(service);                                   // a healthy queue again
        installSimulatedClock();
        service.run(ist(12, 11, 9, 0));
        assertTrue(recipients().contains("n8_s1@scope.test"), "the next daily slot is delivered");
    }

    // ── claim-loop exception safety ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A database failure PART-WAY through claiming: the reminders already claimed are queued and sent (not stranded CLAIMED); the rest are claimed by the next tick; nobody is reminded twice")
    public void claimLoopFailurePartWay() {
        Task task = task("Four people", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1, s2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 3) throw new QueryTimeoutException("database hiccup");
            return invocation.callRealMethod();
        }).when(service).claim(any(), any());

        RunResult first = service.run(ist(12, 10, 10, 0));
        assertTrue(first.claimingStoppedEarly());
        assertEquals(2, first.claimed());
        assertEquals(2, first.queued());
        assertEquals(2, sent().size(), "the two claimed reminders went out");
        assertTrue(rows(task).stream().noneMatch(r -> r.getStatus() == ReminderStatus.CLAIMED), "none is stranded");
        assertEquals(2, rows(task).stream().filter(r -> r.getStatus() == ReminderStatus.SENT).count());

        Mockito.reset(service);
        installSimulatedClock();
        RunResult second = service.run(ist(12, 10, 10, 15));
        assertEquals(2, second.alreadyClaimed());
        assertEquals(2, second.claimed());
        assertEquals(mails("n8_s1", "n8_s2", "n8_mgr", "n8_mgr_grant"), recipients(), "everyone exactly once across the two ticks");
    }

    @Test
    @DisplayName("A failure on the very FIRST claim leaves nothing behind, and the next tick sends everything")
    public void claimLoopFailureOnTheFirstClaim() {
        Task task = task("First fails", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        doThrow(new QueryTimeoutException("database down")).when(service).claim(any(), any());
        RunResult result = service.run(ist(12, 10, 12, 0));
        assertTrue(result.claimingStoppedEarly());
        assertEquals(0, result.claimed());
        assertTrue(sent().isEmpty());
        assertTrue(rows(task).isEmpty());
        Mockito.reset(service);
        installSimulatedClock();
        service.run(ist(12, 10, 12, 15));
        assertEquals(mails("n8_s1"), recipients());
    }

    @Test
    @DisplayName("Giving claims back deletes ONLY this run's claims: another run's in-flight claim survives a rejected queue")
    public void releaseNeverTouchesAnotherRunsClaim() {
        Task task = task("Shared work", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        // Another scheduler instance has already claimed the Manager's reminder and is still delivering it
        TaskReminderLog foreign = logRepository.saveAndFlush(new TaskReminderLog(task.getId(), userId("n8_mgr"), ReminderKind.OVERDUE, 0,
                "2026-12-10T10:00", LocalDateTime.of(2026, 12, 10, 10, 0)));
        doReturn(false).when(notificationService).enqueueTracked(any(), any(), any(), any());
        RunResult result = service.run(ist(12, 10, 10, 0));
        assertEquals(2, result.rejected(), "this run's claims: s1 and the granted Manager (the first Manager's was already taken)");
        assertEquals(1, result.alreadyClaimed());
        List<TaskReminderLog> left = rows(task);
        assertEquals(1, left.size(), "only the foreign claim is left");
        assertEquals(foreign.getId(), left.get(0).getId());
        assertEquals(ReminderStatus.CLAIMED, left.get(0).getStatus());
    }

    // ── delivery bookkeeping and the overdue cap ─────────────────────────────────────────────────────────────────

    private void runDailySlots(int fromDay, int toDay) {
        service.run(ist(12, 10, 10, 0));                       // slot 0, at the deadline
        for (int day = fromDay; day <= toDay; day++) service.run(ist(12, day, 9, 0));
    }

    private long toS1() {
        return sent().stream().filter(m -> m[0].equals("n8_s1@scope.test")).count();
    }

    @Test
    @DisplayName("The provider ACCEPTS the email but the log update fails (every time): the cap of three still holds, because the stale CLAIMED rows become UNKNOWN and keep counting")
    public void acceptedEmailWithFailedBookkeepingStillCountsTowardsTheCap() {
        Task task = task("Bookkeeping fails", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        doThrow(new QueryTimeoutException("log write failed")).when(service).writeOutcome(any(), any(), any());
        runDailySlots(11, 13);                                 // slots 0, 1, 2, 3
        assertEquals(3, toS1(), "three emails reached the provider, and the fourth slot was refused by the cap");
        List<TaskReminderLog> s1Rows = rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId("n8_s1"))).toList();
        assertEquals(3, s1Rows.size());
        assertTrue(s1Rows.stream().allMatch(r -> r.getStatus() == ReminderStatus.UNKNOWN), "possibly sent, never claimed to be unsent: " + s1Rows.stream().map(TaskReminderLog::getStatus).toList());
        RunResult later = service.run(ist(12, 14, 9, 0));
        assertTrue(later.capped() >= 1);
        assertEquals(3, toS1());
    }

    @Test
    @DisplayName("One row's log write fails (after a retry): the other rows are still recorded, and only that row stays CLAIMED")
    public void bookkeepingFailureIsIsolatedPerRow() {
        Task task = task("Isolated", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1, s2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) throw new QueryTimeoutException("log write failed");   // the first row, both attempts
            return invocation.callRealMethod();
        }).when(service).writeOutcome(any(), any(), any());
        service.run(ist(12, 10, 10, 0));
        assertEquals(4, sent().size());
        List<TaskReminderLog> all = rows(task);
        assertEquals(4, all.size());
        assertEquals(1, all.stream().filter(r -> r.getStatus() == ReminderStatus.CLAIMED).count());
        assertEquals(3, all.stream().filter(r -> r.getStatus() == ReminderStatus.SENT).count());
    }

    @Test
    @DisplayName("A log write that fails ONCE is retried and succeeds")
    public void bookkeepingWriteIsRetried() {
        Task task = task("Retried", LocalTime.of(14, 0), TaskStatus.ASSIGNED, s1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new QueryTimeoutException("blip");
            return invocation.callRealMethod();
        }).when(service).writeOutcome(any(), any(), any());
        service.run(ist(12, 10, 12, 0));
        assertEquals(ReminderStatus.SENT, row(task, "n8_s1").getStatus());
    }

    @Test
    @DisplayName("A CONFIRMED provider refusal is FAILED and not counted: the next daily slots can still send three overdue emails")
    public void confirmedRefusalDoesNotUseTheCap() {
        Task task = task("Refused once", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if ("n8_s1@scope.test".equals(invocation.getArgument(0)) && first.getAndSet(false)) throw new EmailSendingException("SendGrid said 400");
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        runDailySlots(11, 14);                                 // slots 0..4
        List<ReminderStatus> statuses = rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId("n8_s1")))
                .map(TaskReminderLog::getStatus).sorted().toList();
        assertEquals(List.of(ReminderStatus.SENT, ReminderStatus.SENT, ReminderStatus.SENT, ReminderStatus.FAILED), statuses);
        assertEquals(4, toS1(), "one refused attempt, then three accepted: three overdue emails in total");
    }

    @Test
    @DisplayName("An UNKNOWN outcome (the connection timed out) counts as possibly sent: only two more overdue emails follow, never a fourth possible one")
    public void unknownOutcomeUsesTheCap() {
        Task task = task("Timed out once", LocalTime.of(10, 0), TaskStatus.ASSIGNED, s1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if ("n8_s1@scope.test".equals(invocation.getArgument(0)) && first.getAndSet(false)) {
                throw new EmailSendingException("Failed to dispatch", new IOException("read timed out"));
            }
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        runDailySlots(11, 14);
        List<ReminderStatus> statuses = rows(task).stream().filter(r -> r.getRecipientUserId().equals(userId("n8_s1")))
                .map(TaskReminderLog::getStatus).sorted().toList();
        assertEquals(List.of(ReminderStatus.SENT, ReminderStatus.SENT, ReminderStatus.UNKNOWN), statuses);
        assertEquals(3, toS1(), "the timed-out attempt may have been delivered, so the cap leaves room for only two more");
    }

    // ── switched off by default ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("By default (taskotel.reminders.enabled unset) there is no scheduler bean, so nothing is ever scheduled or sent")
    public void disabledByDefault() {
        assertFalse(context.containsBean("taskReminderScheduler"));
        assertTrue(context.getBeansOfType(TaskReminderScheduler.class).isEmpty());
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(), "scheduling itself is not even switched on");
    }
}
