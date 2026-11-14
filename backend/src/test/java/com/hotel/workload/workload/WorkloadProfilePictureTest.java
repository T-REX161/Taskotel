package com.hotel.workload.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Each workload row carries the person's existing profile picture (profileImageUrl, the same value the Staff and Users lists show), so the
 * Dashboard and Workload page can show the photo. Who sees which rows is unchanged. Real security chain, in-memory database, rolled back.
 */
@SpringBootTest
@Transactional
public class WorkloadProfilePictureTest {

    private static final String PICTURE = "https://res.cloudinary.com/example/image/upload/v1/taskotel/profile-pictures/wpic.jpg";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Staff pictured, plain;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department dept = fixtures.department("WPIC_DEPT");
        fixtures.account("wpic_admin", com.hotel.workload.security.Role.ADMIN, true);
        fixtures.manager("wpic_manager", dept);
        pictured = fixtures.staff("wpic_pictured", dept);
        plain = fixtures.staff("wpic_plain", dept);
        User account = pictured.getUser();
        account.setProfileImageUrl(PICTURE);
        userRepository.saveAndFlush(account);
    }

    private List<JsonNode> rows(String username, String role) throws Exception {
        String body = mockMvc.perform(get("/api/workload").with(user(username).roles(role))).andReturn().getResponse().getContentAsString();
        List<JsonNode> list = new ArrayList<>();
        mapper.readTree(body).forEach(list::add);
        return list;
    }

    private static JsonNode rowFor(List<JsonNode> rows, Staff member) {
        return rows.stream().filter(r -> member.getId().toString().equals(r.path("staffId").asText())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Admin and Manager workload rows carry the picture (null for someone without one)")
    void adminAndManager() throws Exception {
        for (String[] who : new String[][] {{"wpic_admin", "ADMIN"}, {"wpic_manager", "MANAGER"}}) {
            List<JsonNode> rows = rows(who[0], who[1]);
            assertEquals(PICTURE, rowFor(rows, pictured).path("profileImageUrl").asText(), who[1]);
            assertTrue(rowFor(rows, plain).has("profileImageUrl") && rowFor(rows, plain).path("profileImageUrl").isNull(), who[1]);
        }
    }

    @Test
    @DisplayName("A Staff member's own row carries their own picture, and only their row is returned")
    void staffOwnRow() throws Exception {
        List<JsonNode> own = rows("wpic_pictured", "STAFF");
        assertEquals(1, own.size());
        assertEquals(PICTURE, own.get(0).path("profileImageUrl").asText());
        List<JsonNode> other = rows("wpic_plain", "STAFF");
        assertEquals(1, other.size());
        assertTrue(other.get(0).path("profileImageUrl").isNull(), "no colleague's picture");
    }
}
