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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** GET /api/profile now also carries the account's email and the staff record's phone number (null when there is none). Rolled back after each test. */
@SpringBootTest
@Transactional
public class ProfileContactTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User account(String username, Role role, boolean verified) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(verified);
        return userRepository.saveAndFlush(user);
    }

    private void staffRecord(User user, String phone) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Name " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setPhone(phone);
        staff.setDepartment(TestDepartments.HOUSEKEEPING);
        staff.setAvailability(Availability.AVAILABLE);
        staffRepository.saveAndFlush(staff);
    }

    private String profile(User as) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/profile").with(user(as.getUsername()).roles(as.getRole().name()))).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("Every role gets its own registered email, and the real verification flag")
    void emailAndVerification() throws Exception {
        for (Role role : Role.values()) {
            User verified = account("pc_" + role.name().toLowerCase() + "_ok", role, true);
            User unverified = account("pc_" + role.name().toLowerCase() + "_new", role, false);
            String ok = profile(verified);
            assertTrue(ok.contains("\"email\":\"" + verified.getEmail() + "\"") && ok.contains("\"emailVerified\":true"), ok);
            String no = profile(unverified);
            assertTrue(no.contains("\"email\":\"" + unverified.getEmail() + "\"") && no.contains("\"emailVerified\":false"), no);
            assertFalse(no.contains(verified.getEmail()), "never another account's email");
        }
    }

    @Test
    @DisplayName("The phone number is the staff record's, as stored; an account without a staff record, or with a blank number, gets null")
    void phone() throws Exception {
        User withPhone = account("pc_phone", Role.STAFF, true);
        staffRecord(withPhone, "  +1 555 0100 ");
        assertTrue(profile(withPhone).contains("\"phone\":\"+1 555 0100\""), "trimmed, as stored");

        User blank = account("pc_blank", Role.MANAGER, true);
        staffRecord(blank, "   ");
        assertTrue(profile(blank).contains("\"phone\":null"));

        User none = account("pc_none", Role.STAFF, true);
        staffRecord(none, null);
        assertTrue(profile(none).contains("\"phone\":null"));

        User orphan = account("pc_orphan", Role.ADMIN, true);
        assertTrue(profile(orphan).contains("\"phone\":null"), "no staff record, no phone: nothing is invented");
    }

    @Test
    @DisplayName("The profile still has no password, hash, code or token, and the other users' phones are never included")
    void noSecretsAndNoOtherUsers() throws Exception {
        User me = account("pc_me", Role.STAFF, true);
        staffRecord(me, "111-1111");
        User other = account("pc_other", Role.STAFF, true);
        staffRecord(other, "222-2222");
        String json = profile(me).toLowerCase();
        // "passwordChangedAt" is a date and is allowed; a password or hash field is not
        for (String forbidden : new String[] {"\"password\"", "passwordhash", "\"hash", "token", "verificationcode", "222-2222", other.getEmail()}) {
            assertFalse(json.contains(forbidden), forbidden);
        }
    }
}
