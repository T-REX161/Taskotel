package com.hotel.workload.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.JwtUtil;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The timestamp contract of the real API, over HTTP with real JWTs against real (rolled-back) records, in the JVM zone of production (UTC) and
 * of a developer machine (Asia/Kolkata): every date-time any endpoint sends is an instant with a zone marker, and the plain dates and times of
 * day (a task's due date and deadline) are untouched. Each response body is walked: a single naive date-time string anywhere fails the test.
 */
@SpringBootTest
@Transactional
public class TimestampContractTest {

    /** A date and time with no zone: the shape that made a UTC server's clock reading look like the viewer's. */
    private static final Pattern NAIVE_DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");
    private static final Pattern INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private final TimeZone original = TimeZone.getDefault();
    private MockMvc mvc;
    private Department home;
    private Department granted;
    private User admin;
    private Staff manager;
    private Staff staff;
    private Task task;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department("TC_HOME");
        granted = fixtures.department("TC_GRANT");
        admin = fixtures.account("tc_admin", Role.ADMIN, true);
        manager = fixtures.manager("tc_mgr", home);
        staff = fixtures.staff("tc_staff", home);

        task = new Task();
        task.setTitle("Contract task");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 15));
        task.setDeadlineTime(java.time.LocalTime.of(14, 0));
        task.setDepartment(home);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    /** Sign-in and password-change readings, written in the zone the JVM is in right now (as the application writes them). */
    private void seedActivity() {
        staff.getUser().setLastLoginAt(java.time.LocalDateTime.now().minusHours(2));
        staff.getUser().setPasswordChangedAt(java.time.LocalDateTime.now().minusHours(3));
        userRepository.saveAndFlush(staff.getUser());
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    private String bearer(User user) {
        // a token exactly as sign-in issues it (with the password and session versions of the account as stored now)
        User current = userRepository.findById(user.getId()).orElseThrow();
        return "Bearer " + jwt.generateToken(current.getUsername(), current.getRole(), current.getPasswordChangedAt(), current.getSessionsValidAfter());
    }

    private JsonNode call(HttpMethod method, String url, User as, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url).header("Authorization", bearer(as));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MvcResult result = mvc.perform(builder).andReturn();
        assertEquals(200, result.getResponse().getStatus(), method + " " + url + ": " + result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Every string value in the body that looks like a date-time, with where it was found. */
    private static void collect(JsonNode node, String path, List<String> naive, List<String> instants) {
        if (node.isTextual()) {
            String text = node.asText();
            if (NAIVE_DATE_TIME.matcher(text).matches()) naive.add(path + " = " + text);
            else if (INSTANT.matcher(text).matches()) instants.add(path + " = " + text);
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collect(node.get(i), path + "[" + i + "]", naive, instants);
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collect(entry.getValue(), path + "." + entry.getKey(), naive, instants));
        }
    }

    private int checked;

    private void assertContract(String label, JsonNode body, int atLeastInstants) {
        List<String> naive = new ArrayList<>();
        List<String> instants = new ArrayList<>();
        collect(body, label, naive, instants);
        assertTrue(naive.isEmpty(), "naive date-times sent (no zone marker): " + naive);
        assertTrue(instants.size() >= atLeastInstants, label + " should carry at least " + atLeastInstants + " instants, found " + instants);
        checked += instants.size();
    }

    private void walkEveryEndpoint() throws Exception {
        // an Admin session
        assertContract("GET /api/profile (admin)", call(HttpMethod.GET, "/api/profile", admin, null), 1);
        assertContract("GET /api/users", call(HttpMethod.GET, "/api/users", admin, null), 3);
        assertContract("GET /api/staff", call(HttpMethod.GET, "/api/staff", admin, null), 0);
        JsonNode tasks = call(HttpMethod.GET, "/api/tasks", admin, null);
        assertContract("GET /api/tasks", tasks, 2);
        assertContract("GET /api/assignments", call(HttpMethod.GET, "/api/assignments", admin, null), 1);
        assertContract("GET /api/system-settings", call(HttpMethod.GET, "/api/system-settings", admin, null), 0);
        assertContract("GET /api/workload/recommendations", call(HttpMethod.GET, "/api/workload/recommendations", admin, null), 1);
        assertContract("GET /api/support/assignments", call(HttpMethod.GET, "/api/support/assignments", admin, null), 0);
        // the plain date and time of day on a task are NOT instants: they are sent as they were
        JsonNode sent = null;
        for (JsonNode t : tasks) if ("Contract task".equals(t.get("title").asText())) sent = t;
        assertEquals("2027-01-15", sent.get("dueDate").asText(), "a due date is a calendar date");
        assertEquals("14:00:00", sent.get("deadlineTime").asText(), "a deadline is a time of day");

        // histories: a view-access grant, a role change, a Manager department change
        call(HttpMethod.PUT, "/api/users/" + manager.getUser().getId() + "/view-departments", admin, Map.of("departments", List.of(granted.getCode())));
        String viewUrl = "/api/users/" + manager.getUser().getId() + "/view-departments";
        assertContract("GET view-departments", call(HttpMethod.GET, viewUrl, admin, null), 1);
        assertContract("GET view-departments/history", call(HttpMethod.GET, viewUrl + "/history", admin, null), 1);
        assertContract("GET view-departments/history/page", call(HttpMethod.GET, viewUrl + "/history/page", admin, null), 1);
        call(HttpMethod.PUT, "/api/users/" + staff.getUser().getId() + "/role", admin, Map.of("role", "MANAGER"));
        assertContract("GET role-history", call(HttpMethod.GET, "/api/users/" + staff.getUser().getId() + "/role-history", admin, null), 1);
        call(HttpMethod.PUT, "/api/users/" + manager.getUser().getId() + "/manager-department", admin, Map.of("department", granted.getCode()));
        assertContract("GET manager-department/history", call(HttpMethod.GET, "/api/users/" + manager.getUser().getId() + "/manager-department/history", admin, null), 1);
    }

    @Test
    @DisplayName("Production zone (UTC): no endpoint sends a date-time without its zone, and dates and times of day are untouched")
    void everyEndpointCarriesTheZoneInUtc() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        seedActivity();
        walkEveryEndpoint();
        assertTrue(checked >= 10, "the walk really saw timestamps: " + checked);
    }

    @Test
    @DisplayName("Developer machine zone (Asia/Kolkata): the same holds, and the instants are really the stored readings shifted by the zone")
    void everyEndpointCarriesTheZoneInKolkata() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        seedActivity();
        // before anything changes the account: the instant is the stored reading in the zone it was written in
        JsonNode profile = call(HttpMethod.GET, "/api/profile", staffSession(), null);
        Instant expected = staff.getUser().getLastLoginAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        assertEquals(expected, Instant.parse(profile.get("lastLoginAt").asText()), "a reading written in India is the moment it was, not 5h30 later");
        walkEveryEndpoint();
        assertTrue(checked >= 10, "the walk really saw timestamps: " + checked);
    }

    @Test
    @DisplayName("A Staff session sees zone-marked instants on their profile, dashboard and own tasks too")
    void staffEndpointsCarryTheZone() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        seedActivity();
        User member = staffSession();
        assertContract("GET /api/profile (staff)", call(HttpMethod.GET, "/api/profile", member, null), 3);
        assertContract("GET /api/dashboard/summary (staff)", call(HttpMethod.GET, "/api/dashboard/summary", member, null), 1);
        assertContract("GET /api/tasks (staff)", call(HttpMethod.GET, "/api/tasks", member, null), 2);
    }

    private User staffSession() {
        return userRepository.findById(staff.getUser().getId()).orElseThrow();
    }
}
