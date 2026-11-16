package com.hotel.workload.notification;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Accounts created through the real "create account" endpoint start with every optional email category ON (SECURITY is always on), for
 * each role that can be created; accounts that already existed keep their own behaviour.
 */
@SpringBootTest
public class NewAccountNotificationDefaultsTest {

    private static final String DEPT = "NA_DEPT";
    private static final String[] NAMES = {"na_admin", "na_new_staff", "na_new_manager", "na_old_staff"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private NotificationPreferenceService preferences;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        fixtures.department(DEPT);
        fixtures.account("na_admin", Role.ADMIN, true);
    }

    @AfterEach
    public void cleanUp() {
        for (String name : NAMES) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        fixtures.remove(NAMES);
        departmentRepository.findByCode(DEPT).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    private MvcResult create(String username, String role) throws Exception {
        String body = "{\"fullName\":\"NA " + username + "\",\"email\":\"" + username + "@scope.test\",\"phone\":\"555-0101\",\"department\":\"" + DEPT
                + "\",\"availability\":\"AVAILABLE\",\"username\":\"" + username + "\",\"temporaryPassword\":\"Str0ng!Passw0rd#\",\"role\":\"" + role + "\"}";
        return mockMvc.perform(post("/api/staff").with(user("na_admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private Map<String, Boolean> savedRows(String username) {
        User account = userRepository.findByUsername(username).orElseThrow();
        return preferenceRepository.findByUserId(account.getId()).stream().collect(Collectors.toMap(NotificationPreference::getCategory, NotificationPreference::isEmail));
    }

    @Test
    @DisplayName("A new Staff and a new Manager account start with TASK_ASSIGNED, TASK_STATUS, WORKLOAD_ALERT and TASK_REMINDER on; SECURITY is on and needs no row")
    public void newAccountsStartWithEverythingOn() throws Exception {
        for (String[] account : new String[][] {{"na_new_staff", "STAFF"}, {"na_new_manager", "MANAGER"}}) {
            MvcResult result = create(account[0], account[1]);
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            assertEquals(Map.of("TASK_ASSIGNED", true, "TASK_STATUS", true, "WORKLOAD_ALERT", true, "TASK_REMINDER", true), savedRows(account[0]), account[1]);
            User created = userRepository.findByUsername(account[0]).orElseThrow();
            for (NotificationCategory category : NotificationCategory.values()) assertTrue(preferences.isEmailEnabled(created.getId(), category), category.name());
            // what the user sees on the Settings page
            String json = mockMvc.perform(get("/api/notification-preferences").with(user(account[0]).roles(account[1]))).andReturn().getResponse().getContentAsString();
            assertEquals(5, json.split("\"category\"").length - 1);
            assertFalse(json.contains("\"email\":false"), json);
        }
    }

    @Test
    @DisplayName("A refused account creation leaves no preference rows behind")
    public void refusedCreationLeavesNoRows() throws Exception {
        assertEquals(201, create("na_new_staff", "STAFF").getResponse().getStatus());
        long before = preferenceRepository.count();
        MvcResult duplicate = create("na_new_staff", "STAFF"); // the same username again
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals(before, preferenceRepository.count());
    }

    @Test
    @DisplayName("An account that already existed keeps its old defaults (no rows are created for it)")
    public void existingAccountsAreUntouched() {
        fixtures.staff("na_old_staff", departmentRepository.findByCode(DEPT).orElseThrow());
        User existing = userRepository.findByUsername("na_old_staff").orElseThrow();
        assertTrue(preferenceRepository.findByUserId(existing.getId()).isEmpty());
        assertTrue(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_ASSIGNED));
        assertFalse(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_STATUS));
        assertFalse(preferences.isEmailEnabled(existing.getId(), NotificationCategory.TASK_REMINDER));
    }
}
