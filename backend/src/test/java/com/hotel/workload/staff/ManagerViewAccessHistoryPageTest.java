package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The paged, filtered access history ({@code GET /api/users/{id}/view-departments/history/page}) over HTTP with real JWTs: Admin only,
 * newest first, pages that neither overlap nor skip, the All / Granted / Revoked and department filters with their totals, bounded page
 * sizes, and no write of any kind. The records are created through the real grant endpoint, as in use.
 */
@SpringBootTest
@Transactional
public class ManagerViewAccessHistoryPageTest {

    private static final String HOME = "VH_HOME";
    private static final String DEPT_A = "VH_A";
    private static final String DEPT_B = "VH_B";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private com.hotel.workload.staff.DepartmentRepository departmentRepository;
    @Autowired private ManagerViewGrantChangeRepository changeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwt;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private User managerUser;
    private String admin;
    private String manager;
    private String otherManager;
    private String staffMember;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        var home = fixtures.department(HOME);
        fixtures.department(DEPT_A);
        var deptB = fixtures.department(DEPT_B);
        fixtures.account("vh_admin", Role.ADMIN, true);
        managerUser = fixtures.manager("vh_mgr", home).getUser();
        String other = fixtures.manager("vh_mgr_b", deptB).getUser().getUsername();
        fixtures.staff("vh_staff", home);
        admin = "Bearer " + jwt.generateToken("vh_admin", Role.ADMIN);
        manager = "Bearer " + jwt.generateToken("vh_mgr", Role.MANAGER);
        otherManager = "Bearer " + jwt.generateToken(other, Role.MANAGER);
        staffMember = "Bearer " + jwt.generateToken("vh_staff", Role.STAFF);
    }

    private MvcResult call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url);
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn();
    }

    private String pageUrl(String query) {
        return "/api/users/" + managerUser.getId() + "/view-departments/history/page" + (query.isEmpty() ? "" : "?" + query);
    }

    private JsonNode page(String query) throws Exception {
        MvcResult result = call(HttpMethod.GET, pageUrl(query), admin, null);
        assertEquals(200, result.getResponse().getStatus(), query + ": " + result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void grantAs(String... codes) throws Exception {
        MvcResult result = call(HttpMethod.PUT, "/api/users/" + managerUser.getId() + "/view-departments", admin,
                Map.of("departments", List.of(codes)));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** 25 records: Department A granted and revoked 12 times each (alternating), then Department B granted once (the newest). */
    private void makeHistory() throws Exception {
        for (int i = 0; i < 12; i++) {
            grantAs(DEPT_A);
            grantAs();
        }
        grantAs(DEPT_B);
        assertEquals(25, changeRepository.findByManagerUserIdOrderByChangedAtDesc(managerUser.getId()).size());
    }

    @Test
    @DisplayName("No history: an empty first page with zero totals")
    void emptyHistory() throws Exception {
        JsonNode first = page("");
        assertEquals(0, first.get("items").size());
        assertEquals(0, first.get("totalElements").asLong());
        assertFalse(first.get("hasMore").asBoolean());
        assertEquals(0, first.get("counts").get("all").asLong());
        assertEquals(0, first.get("departments").size());
        assertEquals(10, first.get("size").asInt(), "ten records per page unless asked otherwise");
    }

    @Test
    @DisplayName("Pages of ten, newest first, never overlapping or skipping; together exactly the full history")
    void pagesNewestFirst() throws Exception {
        makeHistory();
        List<String> ids = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        int[] expectedSizes = {10, 10, 5};
        for (int p = 0; p < 3; p++) {
            JsonNode page = page("page=" + p);
            assertEquals(expectedSizes[p], page.get("items").size());
            assertEquals(25, page.get("totalElements").asLong());
            assertEquals(p < 2, page.get("hasMore").asBoolean());
            for (JsonNode item : page.get("items")) {
                ids.add(item.get("id").asText());
                times.add(LocalDateTime.parse(item.get("changedAt").asText()));
            }
        }
        assertEquals(25, new HashSet<>(ids).size(), "no record appears twice");
        Set<String> all = new HashSet<>();
        changeRepository.findByManagerUserIdOrderByChangedAtDesc(managerUser.getId()).forEach(c -> all.add(c.getId().toString()));
        assertEquals(all, new HashSet<>(ids), "no record is skipped");
        for (int i = 1; i < times.size(); i++) assertFalse(times.get(i).isAfter(times.get(i - 1)), "newest first at " + i);

        JsonNode newest = page("").get("items").get(0);
        assertEquals("GRANTED", newest.get("action").asText());
        assertEquals(DEPT_B, newest.get("departmentCode").asText());
        assertEquals("vh_admin", newest.get("changedByUsername").asText());
        assertEquals(0, page("page=3").get("items").size());
    }

    @Test
    @DisplayName("All, Granted and Revoked filters, the department filter, their totals and the department list")
    void filters() throws Exception {
        makeHistory();
        JsonNode all = page("action=ALL");
        assertEquals(25, all.get("totalElements").asLong());
        assertEquals(25, all.get("counts").get("all").asLong());
        assertEquals(13, all.get("counts").get("granted").asLong());
        assertEquals(12, all.get("counts").get("revoked").asLong());

        JsonNode granted = page("action=GRANTED&size=100");
        assertEquals(13, granted.get("totalElements").asLong());
        granted.get("items").forEach(item -> assertEquals("GRANTED", item.get("action").asText()));
        JsonNode revoked = page("action=revoked&size=100");
        assertEquals(12, revoked.get("totalElements").asLong());
        revoked.get("items").forEach(item -> assertEquals("REVOKED", item.get("action").asText()));

        JsonNode onlyB = page("department=" + DEPT_B);
        assertEquals(1, onlyB.get("totalElements").asLong());
        assertEquals(1, onlyB.get("counts").get("all").asLong());
        assertEquals(0, onlyB.get("counts").get("revoked").asLong());
        JsonNode aRevoked = page("department=" + DEPT_A + "&action=REVOKED");
        assertEquals(12, aRevoked.get("totalElements").asLong());
        aRevoked.get("items").forEach(item -> assertEquals(DEPT_A, item.get("departmentCode").asText()));

        JsonNode departments = all.get("departments");
        assertEquals(2, departments.size());
        assertEquals(DEPT_A, departments.get(0).get("code").asText());
        assertEquals(DEPT_A + " name", departments.get(0).get("name").asText());
        assertEquals(DEPT_B, departments.get(1).get("code").asText());
        assertEquals(0, page("department=VH_NONE").get("totalElements").asLong());
    }

    @Test
    @DisplayName("Page size is kept within 1..100 and a negative page reads as the first; an unknown action is 400")
    void boundedRequests() throws Exception {
        makeHistory();
        assertEquals(100, page("size=100000").get("size").asInt());
        assertEquals(25, page("size=100000").get("items").size());
        assertEquals(1, page("size=0").get("items").size());
        assertEquals(0, page("page=-4").get("page").asInt());
        assertEquals(400, call(HttpMethod.GET, pageUrl("action=DELETED"), admin, null).getResponse().getStatus());
    }

    @Test
    @DisplayName("Admin only: Managers (even about themselves) and Staff are 403, anonymous 401; no method changes the history")
    void adminOnlyAndReadOnly() throws Exception {
        makeHistory();
        long before = changeRepository.count();
        for (String caller : List.of(manager, otherManager, staffMember)) {
            assertEquals(403, call(HttpMethod.GET, pageUrl(""), caller, null).getResponse().getStatus());
            assertEquals(403, call(HttpMethod.GET, pageUrl("action=GRANTED"), caller, null).getResponse().getStatus());
        }
        assertEquals(401, call(HttpMethod.GET, pageUrl(""), null, null).getResponse().getStatus());
        for (HttpMethod method : List.of(HttpMethod.DELETE, HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH)) {
            int status = call(method, pageUrl(""), admin, Map.of()).getResponse().getStatus();
            assertTrue(status >= 400, method + " on the history page must be refused, was " + status);
            int plain = call(method, "/api/users/" + managerUser.getId() + "/view-departments/history", admin, Map.of()).getResponse().getStatus();
            assertTrue(plain >= 400, method + " on the history must be refused, was " + plain);
        }
        page("");
        page("action=REVOKED&department=" + DEPT_A + "&page=1");
        assertEquals(before, changeRepository.count(), "reading and refused requests leave the history as it was");
    }
}
