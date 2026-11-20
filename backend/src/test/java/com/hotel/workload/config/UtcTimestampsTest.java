package com.hotel.workload.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.JwtUtil;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Timestamps are sent with their zone. Regression for the production-only discrepancy: the server stored a clock reading in its own zone
 * and sent it with no zone marker, so the browser could not tell a UTC server's 05:25 from India's 05:25, and the Account Activity card showed
 * the server's clock (05:25) instead of the viewer's (10:55 in Asia/Kolkata).
 */
@SpringBootTest
@Transactional
public class UtcTimestampsTest {

    private final TimeZone original = TimeZone.getDefault();

    @Autowired private JsonMapper mapper;
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    public record Sample(LocalDateTime moment, LocalDate day, LocalTime timeOfDay) {}

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("A stored reading becomes the right instant for the zone it was written in: UTC server 05:25 and an India machine's 10:55 are the same moment")
    void readingBecomesAnInstant() {
        assertEquals("2026-11-20T05:25:00Z", UtcTimestamps.asInstant(LocalDateTime.of(2026, 11, 20, 5, 25), ZoneId.of("UTC")));
        assertEquals("2026-11-20T05:25:00Z", UtcTimestamps.asInstant(LocalDateTime.of(2026, 11, 20, 10, 55), ZoneId.of("Asia/Kolkata")));
        assertEquals("2026-11-20T05:25:00Z", UtcTimestamps.asInstant(LocalDateTime.of(2026, 11, 20, 0, 25), ZoneId.of("America/New_York")));
        assertEquals("2026-11-20T06:10:30.123Z", UtcTimestamps.asInstant(LocalDateTime.of(2026, 11, 20, 6, 10, 30, 123_000_000), ZoneId.of("UTC")), "fractions kept");
    }

    @Test
    @DisplayName("The API sends a LocalDateTime as an instant with a Z in every JVM zone; dates and times of day are untouched")
    void jsonCarriesTheZone() throws Exception {
        String[][] cases = {{"UTC", "2026-11-20T05:25:00Z"}, {"Asia/Kolkata", "2026-11-19T23:55:00Z"}, {"America/New_York", "2026-11-20T10:25:00Z"}};
        for (String[] c : cases) {
            TimeZone.setDefault(TimeZone.getTimeZone(c[0])); // the same stored reading, read in the zone the machine writes in
            JsonNode json = new ObjectMapper().readTree(mapper.writeValueAsString(
                    new Sample(LocalDateTime.of(2026, 11, 20, 5, 25, 0), LocalDate.of(2026, 11, 20), LocalTime.of(14, 0))));
            assertEquals(c[1], json.get("moment").asText(), c[0]);
            assertEquals("2026-11-20", json.get("day").asText(), c[0] + ": a due date is a calendar date, not a moment");
            assertEquals("14:00:00", json.get("timeOfDay").asText(), c[0] + ": a deadline is a time of day, not a moment");
        }
    }

    @Test
    @DisplayName("The profile API (Account Created, Last Login, Last Password Change) answers with zone-marked instants: what a UTC production server stores reaches the browser as UTC")
    void profileTimestampsCarryTheZone() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); // the production container
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        User user = fixtures.account("tz_user", Role.ADMIN, true);
        user.setLastLoginAt(LocalDateTime.of(2026, 11, 20, 6, 10, 0));
        user.setPasswordChangedAt(LocalDateTime.of(2026, 11, 20, 5, 27, 0));
        userRepository.saveAndFlush(user);

        MvcResult result = mvc.perform(get("/api/profile").header("Authorization", "Bearer " + jwt.generateToken("tz_user", Role.ADMIN))).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode profile = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertEquals("2026-11-20T06:10:00Z", profile.get("lastLoginAt").asText());
        assertEquals("2026-11-20T05:27:00Z", profile.get("passwordChangedAt").asText());
        assertTrue(profile.get("createdAt").asText().endsWith("Z"), "Account Created: " + profile.get("createdAt"));
    }
}
