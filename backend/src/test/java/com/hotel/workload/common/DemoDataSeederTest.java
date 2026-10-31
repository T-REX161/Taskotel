package com.hotel.workload.common;

import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
import com.hotel.workload.task.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Demo seeding is opt-in (app.demo-data.enabled, default false), never runs in production, and never when users exist. */
class DemoDataSeederTest {

    private UserRepository users;
    private StaffRepository staff;
    private TaskRepository tasks;
    private AssignmentRepository assignments;
    private DepartmentRepository departments;
    private PasswordEncoder encoder;
    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        staff = mock(StaffRepository.class);
        tasks = mock(TaskRepository.class);
        assignments = mock(AssignmentRepository.class);
        departments = mock(DepartmentRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("encoded");
        when(departments.findByCode(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            return TestDepartments.ALL.stream().filter(d -> d.getCode().equals(code)).findFirst();
        });
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(staff.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private DemoDataSeeder seeder(MockEnvironment environment) {
        return new DemoDataSeeder(users, staff, tasks, assignments, encoder, environment, departments);
    }

    private String output() {
        return captured.toString();
    }

    private void assertNothingCreated() {
        verify(users, never()).save(any());
        verify(staff, never()).save(any());
        verify(tasks, never()).save(any());
        verify(assignments, never()).save(any());
    }

    @Test
    @DisplayName("Disabled by default: no property means no demo users, and the log line says so without any credentials")
    void disabledByDefault() throws Exception {
        seeder(new MockEnvironment()).run();
        assertNothingCreated();
        verify(encoder, never()).encode(any());
        assertTrue(output().contains("Demo data seeding is disabled"), output());
        assertTrue(output().contains("app.demo-data.enabled"));
        assertFalse(output().toLowerCase().contains("password"), "no credentials in the log");
    }

    @Test
    @DisplayName("Explicitly false, or no environment at all, also seeds nothing")
    void explicitlyDisabledOrNoEnvironment() throws Exception {
        seeder(new MockEnvironment().withProperty("app.demo-data.enabled", "false")).run();
        new DemoDataSeeder(users, staff, tasks, assignments, encoder, departments).run();
        assertNothingCreated();
    }

    @Test
    @DisplayName("Enabled in a permitted (non-production) environment on an empty database: the demo data is created")
    void enabledSeeds() throws Exception {
        when(users.count()).thenReturn(0L);
        seeder(new MockEnvironment().withProperty("app.demo-data.enabled", "true")).run();
        verify(users, org.mockito.Mockito.atLeast(5)).save(any(User.class));
        verify(staff, org.mockito.Mockito.atLeastOnce()).save(any());
        verify(tasks, org.mockito.Mockito.atLeastOnce()).save(any());
        assertTrue(output().contains("Demo data inserted successfully"), output());
        assertFalse(output().toLowerCase().contains("password"), "the seeding log never prints credentials");
    }

    @Test
    @DisplayName("Never under the prod or production profile, even when enabled")
    void neverInProduction() throws Exception {
        for (String profile : new String[] {"prod", "production", "PROD"}) {
            MockEnvironment environment = new MockEnvironment().withProperty("app.demo-data.enabled", "true");
            environment.setActiveProfiles(profile);
            when(users.count()).thenReturn(0L);
            seeder(environment).run();
        }
        assertNothingCreated();
        verify(users, never()).count();
        assertTrue(output().contains("Production profile detected"), output());
    }

    @Test
    @DisplayName("Enabled but users already exist: nothing is duplicated or overwritten")
    void existingUsersPreventSeeding() throws Exception {
        when(users.count()).thenReturn(3L);
        seeder(new MockEnvironment().withProperty("app.demo-data.enabled", "true")).run();
        assertNothingCreated();
        verifyNoInteractions(staff, tasks, assignments);
        assertTrue(output().contains("Demo data already exists"), output());
    }

    @Test
    @DisplayName("Configuration: the property defaults to false, can be set by DEMO_DATA_ENABLED, and no profile file turns it on")
    void configurationDefaults() throws Exception {
        Path resources = Path.of("src/main/resources");
        String main = Files.readString(resources.resolve("application.yml"));
        assertTrue(main.contains("demo-data:") && main.contains("enabled: ${DEMO_DATA_ENABLED:false}"));
        for (String file : new String[] {"application-prod.yml", "application-production.yml"}) {
            assertFalse(Files.readString(resources.resolve(file)).contains("demo-data"), file + " must not enable demo data");
        }
        assertEquals("app.demo-data.enabled", DemoDataSeeder.ENABLED_PROPERTY);
        Optional<String> testConfig = Files.readString(Path.of("src/test/resources/application.yml")).contains("demo-data") ? Optional.of("set") : Optional.empty();
        assertTrue(testConfig.isEmpty(), "the test configuration does not turn demo seeding on");
    }
}
