package com.hotel.workload.profile;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.TestDepartments;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** PUT /api/profile/phone: the signed-in user's own phone number. Rolled back after each test. */
@SpringBootTest
@Transactional
public class PhoneUpdateTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User account(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private Staff staffRecord(User user, String phone) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Name " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setPhone(phone);
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        return staffRepository.saveAndFlush(staff);
    }

    private MvcResult save(User as, String body) throws Exception {
        return mockMvc.perform(put("/api/profile/phone").with(user(as.getUsername()).roles(as.getRole().name()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String phoneJson(String phone) {
        return "{\"phone\":" + (phone == null ? "null" : "\"" + phone + "\"") + "}";
    }

    private String profile(User as) throws Exception {
        return mockMvc.perform(get("/api/profile").with(user(as.getUsername()).roles(as.getRole().name()))).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("An Admin or Manager without a staff record can save a phone number; it is returned at once and after a fresh read")
    void accountsWithoutStaffRecord() throws Exception {
        for (Role role : new Role[] {Role.ADMIN, Role.MANAGER}) {
            User account = account("ph_" + role.name().toLowerCase(), role);
            MvcResult result = save(account, phoneJson("+91 98765 43210"));
            assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            assertTrue(result.getResponse().getContentAsString().contains("\"phone\":\"+91 98765 43210\""));
            assertTrue(profile(account).contains("\"phone\":\"+91 98765 43210\""), "persists");
            assertEquals("+91 98765 43210", userRepository.findByUsername(account.getUsername()).orElseThrow().getPhone());
        }
    }

    @Test
    @DisplayName("Staff with a linked staff record: the number is saved on the account and the staff record, and a later change replaces both")
    void staffWithRecord() throws Exception {
        User account = account("ph_staff", Role.STAFF);
        Staff staff = staffRecord(account, "111-1111-11");
        assertEquals(200, save(account, phoneJson("(555) 010-2030")).getResponse().getStatus());
        assertEquals("(555) 010-2030", staffRepository.findById(staff.getId()).orElseThrow().getPhone());
        assertEquals("(555) 010-2030", userRepository.findByUsername("ph_staff").orElseThrow().getPhone());
        assertTrue(profile(account).contains("\"phone\":\"(555) 010-2030\""));
        assertEquals(200, save(account, phoneJson("+44 20 7946 0958")).getResponse().getStatus());
        assertEquals("+44 20 7946 0958", staffRepository.findById(staff.getId()).orElseThrow().getPhone());
    }

    @Test
    @DisplayName("The number is trimmed and inner spaces collapsed")
    void normalised() throws Exception {
        User account = account("ph_trim", Role.ADMIN);
        MvcResult result = save(account, phoneJson("  +1   555 010 2030  "));
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"phone\":\"+1 555 010 2030\""));
    }

    @Test
    @DisplayName("Empty, missing and null input is refused (as for the display name) and the saved number is kept")
    void emptyInput() throws Exception {
        User account = account("ph_empty", Role.MANAGER);
        staffRecord(account, "555-0100-22");
        save(account, phoneJson("+1 555 010 2030"));
        for (String body : new String[] {phoneJson(""), phoneJson("   "), phoneJson(null), "{}"}) {
            MvcResult result = save(account, body);
            assertEquals(400, result.getResponse().getStatus(), body);
            assertTrue(result.getResponse().getContentAsString().contains(PhoneNumbers.EMPTY_MESSAGE), body);
        }
        assertTrue(profile(account).contains("\"phone\":\"+1 555 010 2030\""));
    }

    @Test
    @DisplayName("Invalid numbers are refused with 400 and nothing is saved")
    void invalidInput() throws Exception {
        User account = account("ph_bad", Role.STAFF);
        for (String bad : new String[] {"abc", "12345", "+91 98765 abc", "1234567890123456", "++911234567", "98765<script>43210", "(123 4567890", "123) 4567890",
                "+", "12 34 56", "1".repeat(51)}) {
            MvcResult result = save(account, phoneJson(bad));
            assertEquals(400, result.getResponse().getStatus(), bad);
        }
        assertNull(userRepository.findByUsername("ph_bad").orElseThrow().getPhone());
        assertTrue(profile(account).contains("\"phone\":null"));
    }

    @Test
    @DisplayName("Accepted formats: country codes, spaces, hyphens, dots and brackets")
    void validFormats() throws Exception {
        User account = account("ph_ok", Role.STAFF);
        for (String ok : new String[] {"+919876543210", "+91 98765-43210", "98765.43210", "(020) 7946 0958", "+1 (555) 010-2030", "5550102030"}) {
            assertEquals(200, save(account, phoneJson(ok)).getResponse().getStatus(), ok);
        }
    }

    @Test
    @DisplayName("User isolation: a user changes only their own number, and a userId in the request is ignored")
    void isolation() throws Exception {
        User me = account("ph_me", Role.STAFF);
        staffRecord(me, "111-1111-11");
        User other = account("ph_other", Role.STAFF);
        Staff otherStaff = staffRecord(other, "222-2222-22");
        User admin = account("ph_other_admin", Role.ADMIN);

        MvcResult result = save(me, "{\"phone\":\"+1 555 010 9999\",\"userId\":\"" + other.getId() + "\",\"username\":\"ph_other\"}");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("222-2222-22", staffRepository.findById(otherStaff.getId()).orElseThrow().getPhone());
        assertNull(userRepository.findByUsername("ph_other").orElseThrow().getPhone());
        assertNull(userRepository.findByUsername("ph_other_admin").orElseThrow().getPhone());
        assertFalse(result.getResponse().getContentAsString().contains("222-2222-22"));
        assertTrue(profile(other).contains("222-2222-22") && !profile(other).contains("9999"));
        assertTrue(admin.getId() != null);
    }

    @Test
    @DisplayName("Unauthenticated requests are refused and change nothing")
    void unauthenticated() throws Exception {
        User account = account("ph_anon", Role.STAFF);
        int status = mockMvc.perform(put("/api/profile/phone").contentType(MediaType.APPLICATION_JSON).content(phoneJson("+1 555 010 2030")))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "status " + status);
        assertNull(userRepository.findByUsername(account.getUsername()).orElseThrow().getPhone());
    }

    @Test
    @DisplayName("The phone number is never described as verified, and the staff record number still shows for accounts that have only that")
    void notVerifiedAndLegacyStaffNumber() throws Exception {
        User account = account("ph_legacy", Role.STAFF);
        staffRecord(account, "  +1 555 0100 ");
        String json = profile(account);
        assertTrue(json.contains("\"phone\":\"+1 555 0100\""), json);
        assertFalse(json.toLowerCase().contains("phoneverified"));
    }
}
