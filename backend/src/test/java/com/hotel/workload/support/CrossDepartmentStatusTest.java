package com.hotel.workload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.settings.SystemSettings;
import com.hotel.workload.settings.SystemSettingsRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * GET /api/support/cross-department-status: the one System Settings fact a Manager may read (whether new cross-department support is on),
 * for Admin and a valid Manager only, returning that single flag and nothing else. The settings themselves stay Admin-only to read and change.
 * Real security chain and H2; every test rolls back.
 */
@SpringBootTest
@Transactional
public class CrossDepartmentStatusTest {

    private static final String URL = "/api/support/cross-department-status";

    @Autowired private WebApplicationContext context;
    @Autowired private SystemSettingsRepository settingsRepository;
    @Autowired private UserRepository users;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private User admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(users, staffRepository, departmentRepository, passwordEncoder);
        Department department = fixtures.department("XDS_D");
        admin = fixtures.account("xds_admin", Role.ADMIN, true);
        fixtures.manager("xds_manager", department);
        fixtures.account("xds_manager_nostaff", Role.MANAGER, true); // an invalid Manager: no staff record
        fixtures.staff("xds_staff", department);
    }

    private void settings(boolean crossDepartment) {
        SystemSettings row = settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
        row.setAutoBalancingEnabled(false);
        row.setCapacityAlertEnabled(false);
        row.setCrossDepartmentAssistanceEnabled(crossDepartment);
        row.setCapacityThresholdPercent(42);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        row.setUpdatedByUserId(admin.getId());
        row.setUpdatedByUsername(admin.getUsername());
        settingsRepository.saveAndFlush(row);
    }

    private MockHttpServletResponse read(String username, String role) throws Exception {
        var request = get(URL);
        if (username != null) request = request.with(user(username).roles(role));
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertOnlyTheFlag(MockHttpServletResponse response, boolean expected) throws Exception {
        assertEquals(200, response.getStatus());
        JsonNode body = mapper.readTree(response.getContentAsString());
        List<String> fields = new ArrayList<>();
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) fields.add(it.next());
        assertEquals(List.of("crossDepartmentAssistanceEnabled"), fields, "only that one flag is returned");
        assertEquals(expected, body.get("crossDepartmentAssistanceEnabled").asBoolean());
        String raw = response.getContentAsString();
        for (String other : new String[] {"capacityThresholdPercent", "capacityAlertEnabled", "autoBalancingEnabled", "updatedBy", "updatedAt"}) {
            assertFalse(raw.contains(other), "no other setting is exposed: " + other);
        }
    }

    @Test
    @DisplayName("Admin and a valid Manager both read the flag: ON and OFF")
    void adminAndManagerReadTheFlag() throws Exception {
        settings(true);
        assertOnlyTheFlag(read("xds_admin", "ADMIN"), true);
        assertOnlyTheFlag(read("xds_manager", "MANAGER"), true);
        settings(false);
        assertOnlyTheFlag(read("xds_admin", "ADMIN"), false);
        assertOnlyTheFlag(read("xds_manager", "MANAGER"), false);
    }

    @Test
    @DisplayName("With no settings saved, the default is reported (cross-department assistance is on)")
    void defaultWhenNothingSaved() throws Exception {
        settingsRepository.deleteAll();
        settingsRepository.flush();
        assertOnlyTheFlag(read("xds_admin", "ADMIN"), true);
        assertOnlyTheFlag(read("xds_manager", "MANAGER"), true);
    }

    @Test
    @DisplayName("Staff, an invalid Manager and anonymous callers are refused (403, 403, 401)")
    void othersAreRefused() throws Exception {
        settings(false);
        assertEquals(403, read("xds_staff", "STAFF").getStatus());
        assertEquals(403, read("xds_manager_nostaff", "MANAGER").getStatus());
        assertEquals(401, read(null, null).getStatus());
    }

    @Test
    @DisplayName("The settings stay Admin-only: a Manager still cannot read them in full or change them, and the new route cannot write")
    void settingsRemainAdminOnly() throws Exception {
        settings(false);
        assertEquals(200, mvc.perform(get("/api/system-settings").with(user("xds_admin").roles("ADMIN"))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/api/system-settings").with(user("xds_manager").roles("MANAGER"))).andReturn().getResponse().getStatus());
        String body = "{\"autoBalancingEnabled\":true,\"capacityAlertEnabled\":true,\"crossDepartmentAssistanceEnabled\":true,\"capacityThresholdPercent\":85}";
        assertEquals(403, mvc.perform(put("/api/system-settings").with(user("xds_manager").roles("MANAGER")).contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(put("/api/system-settings").with(user("xds_staff").roles("STAFF")).contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus());
        // the status route accepts no write at all
        assertTrue(List.of(405, 403).contains(mvc.perform(put(URL).with(user("xds_manager").roles("MANAGER")).contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()));
        // and nothing changed
        assertFalse(settingsRepository.findBySettingsKey(SystemSettings.GLOBAL_KEY).orElseThrow().isCrossDepartmentAssistanceEnabled());
    }
}
