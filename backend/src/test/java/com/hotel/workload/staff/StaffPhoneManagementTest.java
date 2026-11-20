package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Phone numbers in the Staff edit workflow: Admin for every account, a Manager for Staff of their own department only, the account holder
 * through their own profile. The number is optional and only saved (never verified); a changed number must be a valid phone number.
 * Over HTTP with real JWTs, against rolled-back records.
 */
@SpringBootTest
@Transactional
public class StaffPhoneManagementTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private ScopeFixtures fixtures;
    private Department home;
    private Department other;
    private String admin;
    private String manager;
    private String staffToken;
    private Staff staff;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department("PH_HOME");
        other = fixtures.department("PH_OTHER");
        admin = bearer(fixtures.account("ph_admin", Role.ADMIN, true));
        manager = bearer(fixtures.manager("ph_mgr", home).getUser());
        staff = fixtures.staff("ph_staff", home);
        staffToken = bearer(staff.getUser());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateToken(user.getUsername(), user.getRole());
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    /** The edit form's payload for a record: its own values, with the phone replaced. */
    private Map<String, Object> edit(Staff record, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", record.getName());
        body.put("email", record.getEmail());
        body.put("phone", phone);
        body.put("department", record.getDepartment() == null ? null : record.getDepartment().getCode());
        body.put("availability", record.getAvailability().name());
        body.put("active", record.isActive());
        return body;
    }

    private int put(Staff record, String phone, String token) throws Exception {
        return call(HttpMethod.PUT, "/api/staff/" + record.getId(), token, edit(record, phone)).getResponse().getStatus();
    }

    private Staff reload(Staff record) {
        return staffRepository.findById(record.getId()).orElseThrow();
    }

    @Test
    @DisplayName("Admin edits a Staff phone number: saved on the Staff record and the account, normalised, never verified")
    void adminEdits() throws Exception {
        assertEquals(200, put(staff, "  +91   98765 43210 ", admin));
        assertEquals("+91 98765 43210", reload(staff).getPhone());
        assertEquals("+91 98765 43210", userRepository.findById(staff.getUser().getId()).orElseThrow().getPhone(), "one number for the person");
    }

    @Test
    @DisplayName("Admin can also edit a Manager's and another Admin's phone number")
    void adminEditsOtherRoles() throws Exception {
        Staff mgr = fixtures.manager("ph_mgr2", home);
        Staff adm = fixtures.staffRecord("ph_adm2", Role.ADMIN, home);
        assertEquals(200, put(mgr, "555-010-2000", admin));
        assertEquals(200, put(adm, "555-010-3000", admin));
        assertEquals("555-010-2000", reload(mgr).getPhone());
        assertEquals("555-010-3000", reload(adm).getPhone());
    }

    @Test
    @DisplayName("Manager edits the phone number of Staff in their own department")
    void managerEditsOwnDepartment() throws Exception {
        assertEquals(200, put(staff, "(020) 7946 0958", manager));
        assertEquals("(020) 7946 0958", reload(staff).getPhone());
    }

    @Test
    @DisplayName("Manager cannot edit Staff outside their department (404, nothing changes), nor a Manager or Admin account")
    void managerOutsideAndPeersRefused() throws Exception {
        Staff elsewhere = fixtures.staff("ph_elsewhere", other);
        String before = reload(elsewhere).getPhone();
        assertEquals(404, put(elsewhere, "555-010-9999", manager));
        assertEquals(before, reload(elsewhere).getPhone());

        Staff peer = fixtures.manager("ph_peer_mgr", home);
        assertEquals(403, put(peer, "555-010-9999", manager));
        assertEquals(before == null ? null : reload(peer).getPhone(), reload(peer).getPhone());
        assertTrue(!"555-010-9999".equals(reload(peer).getPhone()));
    }

    @Test
    @DisplayName("Staff cannot edit anyone's phone through the staff endpoint; they use their own profile")
    void staffCannotUseStaffEndpoint() throws Exception {
        assertEquals(403, put(staff, "555-010-1111", staffToken));
        assertTrue(!"555-010-1111".equals(reload(staff).getPhone()));
    }

    @Test
    @DisplayName("Staff update their own phone number through their profile; it shows on the Staff record")
    void staffSelfService() throws Exception {
        MvcResult result = call(HttpMethod.PUT, "/api/profile/phone", staffToken, Map.of("phone", "+44 20 7946 0958"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("+44 20 7946 0958", reload(staff).getPhone());
        assertEquals(400, call(HttpMethod.PUT, "/api/profile/phone", staffToken, Map.of("phone", "abc")).getResponse().getStatus());
        assertEquals("+44 20 7946 0958", reload(staff).getPhone(), "a refused number changes nothing");
    }

    @Test
    @DisplayName("An invalid number is refused with 400 and nothing changes (too short, letters, bad brackets, too long)")
    void invalidNumbersRefused() throws Exception {
        assertEquals(200, put(staff, "555-010-0000", admin));
        for (String bad : new String[] {"12345", "call me", "(555 010 0000", "+1 " + "9".repeat(20), "555-010-0000 ext 5"}) {
            assertEquals(400, put(staff, bad, admin), bad);
            assertEquals("555-010-0000", reload(staff).getPhone(), bad);
            assertEquals(400, put(staff, bad, manager), "manager: " + bad);
        }
    }

    @Test
    @DisplayName("The number is optional: blank or missing clears it, and records with no number can be edited")
    void optionalNumber() throws Exception {
        assertEquals(200, put(staff, "555-010-0000", admin));
        assertEquals(200, put(staff, "   ", admin));
        assertNull(reload(staff).getPhone(), "blank clears it");
        assertNull(userRepository.findById(staff.getUser().getId()).orElseThrow().getPhone());
        assertEquals(200, put(staff, null, admin));
        assertNull(reload(staff).getPhone());
        // editing something else on a record with no number works and adds none
        Map<String, Object> body = edit(staff, null);
        body.put("fullName", "Renamed Person");
        MvcResult result = call(HttpMethod.PUT, "/api/staff/" + staff.getId(), admin, body);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("Renamed Person", reload(staff).getName());
        assertNull(reload(staff).getPhone());
    }

    @Test
    @DisplayName("An older record whose stored number would not pass today's rules never blocks an edit of something else")
    void legacyNumberIsKept() throws Exception {
        staff.setPhone("ext 12");
        staffRepository.saveAndFlush(staff);
        Map<String, Object> body = edit(staff, "ext 12");
        body.put("fullName", "Still Editable");
        assertEquals(200, call(HttpMethod.PUT, "/api/staff/" + staff.getId(), admin, body).getResponse().getStatus());
        assertEquals("ext 12", reload(staff).getPhone());
        assertEquals(400, put(staff, "ext 13", admin), "but a different number must be valid");
    }

    @Test
    @DisplayName("Creating Staff takes an optional, validated phone number too")
    void creationValidatesToo() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "New Person");
        body.put("username", "ph_new_person");
        body.put("email", "ph_new_person@hotel.test");
        body.put("temporaryPassword", "Temp@12345x");
        body.put("phone", "nope");
        body.put("department", home.getCode());
        body.put("availability", "AVAILABLE");
        body.put("role", "STAFF");
        MvcResult bad = call(HttpMethod.POST, "/api/staff", admin, body);
        assertEquals(400, bad.getResponse().getStatus(), bad.getResponse().getContentAsString());
        assertTrue(userRepository.findByUsername("ph_new_person").isEmpty(), "a refused number creates nothing");
    }

    @Test
    @DisplayName("Creating Staff with a number stores it on both the Staff record and the account, normalised")
    void createSynchronisesBothRecords() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Synced Person");
        body.put("username", "ph_synced");
        body.put("email", "ph_synced@hotel.test");
        body.put("temporaryPassword", "Temp@12345x");
        body.put("phone", "  +91   98765 43210 ");
        body.put("department", home.getCode());
        body.put("availability", "AVAILABLE");
        body.put("role", "STAFF");
        MvcResult created = call(HttpMethod.POST, "/api/staff", admin, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        assertEquals("+91 98765 43210", staffRepository.findByUserUsername("ph_synced").orElseThrow().getPhone());
        assertEquals("+91 98765 43210", userRepository.findByUsername("ph_synced").orElseThrow().getPhone());

        body.put("username", "ph_no_phone");
        body.put("email", "ph_no_phone@hotel.test");
        body.put("phone", null);
        assertEquals(201, call(HttpMethod.POST, "/api/staff", admin, body).getResponse().getStatus(), "no number is fine");
        assertNull(staffRepository.findByUserUsername("ph_no_phone").orElseThrow().getPhone());
        assertNull(userRepository.findByUsername("ph_no_phone").orElseThrow().getPhone());
    }

    @Test
    @DisplayName("Self-service profile update, an Admin edit and a clearing edit keep the account and the Staff record the same")
    void editsAndSelfServiceStayInStep() throws Exception {
        assertEquals(200, call(HttpMethod.PUT, "/api/profile/phone", staffToken, Map.of("phone", "+44 20 7946 0958")).getResponse().getStatus());
        assertEquals("+44 20 7946 0958", reload(staff).getPhone());
        assertEquals("+44 20 7946 0958", userRepository.findById(staff.getUser().getId()).orElseThrow().getPhone());

        assertEquals(200, put(staff, "555-010-4444", admin));
        assertEquals("555-010-4444", reload(staff).getPhone());
        assertEquals("555-010-4444", userRepository.findById(staff.getUser().getId()).orElseThrow().getPhone());

        assertEquals(200, put(staff, "", manager));
        assertNull(reload(staff).getPhone());
        assertNull(userRepository.findById(staff.getUser().getId()).orElseThrow().getPhone());
    }

    @Test
    @DisplayName("An unrelated edit never touches the account's number: a record whose two numbers differ (or where only the account has one) loses nothing")
    void unrelatedEditLeavesTheAccountNumberAlone() throws Exception {
        User account = staff.getUser();
        account.setPhone("555-010-7777");
        userRepository.saveAndFlush(account);
        staff.setPhone(null);
        staffRepository.saveAndFlush(staff);

        Map<String, Object> body = edit(staff, null);
        body.put("fullName", "Renamed Again");
        assertEquals(200, call(HttpMethod.PUT, "/api/staff/" + staff.getId(), admin, body).getResponse().getStatus());
        assertEquals("Renamed Again", reload(staff).getName());
        assertNull(reload(staff).getPhone());
        assertEquals("555-010-7777", userRepository.findById(account.getId()).orElseThrow().getPhone(), "the account's number is kept");

        // and a legacy number on the Staff record, unchanged, stays on both sides of a different edit
        staff.setPhone("ext 12");
        staffRepository.saveAndFlush(staff);
        Map<String, Object> again = edit(staff, "ext 12");
        again.put("fullName", "Renamed Thrice");
        assertEquals(200, call(HttpMethod.PUT, "/api/staff/" + staff.getId(), manager, again).getResponse().getStatus());
        assertEquals("ext 12", reload(staff).getPhone());
        assertEquals("555-010-7777", userRepository.findById(account.getId()).orElseThrow().getPhone());
    }
}
