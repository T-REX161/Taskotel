package com.hotel.workload.staff;

import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Username availability endpoint and duplicate protection on account creation.
 * Not transactional on purpose: the concurrency test needs real commits, and cleans up after itself.
 */
@SpringBootTest
public class StaffUsernameAvailabilityTest {

    private static final String PREFIX = "avail_test_";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanup();
    }

    @AfterEach
    public void cleanup() {
        for (User user : userRepository.findAll()) {
            if (user.getUsername().startsWith(PREFIX)) {
                staffRepository.findAll().stream()
                        .filter(s -> s.getUser() != null && s.getUser().getId().equals(user.getId()))
                        .forEach(staffRepository::delete);
                userRepository.delete(user);
            }
        }
    }

    private String body(String username, String email) {
        return """
                {"fullName": "Avail Test", "email": "%s", "department": "FRONT_DESK", "availability": "AVAILABLE",
                 "username": "%s", "temporaryPassword": "TempPass@123"}
                """.formatted(email, username);
    }

    private int create(String role, String username, String email) throws Exception {
        return mockMvc.perform(post("/api/staff").with(user("tester").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content(body(username, email)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    public void unusedUsernameIsAvailableAndTakenOneIsNot() throws Exception {
        mockMvc.perform(get("/api/staff/username-availability").param("username", PREFIX + "free").with(user("a").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));

        assertEquals(201, create("ADMIN", PREFIX + "taken", PREFIX + "taken@hotel.com"));

        mockMvc.perform(get("/api/staff/username-availability").param("username", PREFIX + "taken").with(user("a").roles("MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
    }

    @Test
    public void availabilityResponseExposesNothingAboutTheAccount() throws Exception {
        assertEquals(201, create("ADMIN", PREFIX + "private", PREFIX + "private@hotel.com"));
        mockMvc.perform(get("/api/staff/username-availability").param("username", PREFIX + "private").with(user("a").roles("ADMIN")))
                .andExpect(content().json("{\"available\":false}", true));
    }

    @Test
    public void blankOrMissingUsernameIsRejected() throws Exception {
        mockMvc.perform(get("/api/staff/username-availability").param("username", "  ").with(user("a").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/staff/username-availability").with(user("a").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void onlyAdminsAndManagersMayCheck() throws Exception {
        mockMvc.perform(get("/api/staff/username-availability").param("username", "x").with(user("a").roles("STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/staff/username-availability").param("username", "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void duplicateUsernameIsRejectedWithAClearMessageAndKeepsTheFirstAccount() throws Exception {
        assertEquals(201, create("ADMIN", PREFIX + "dup", PREFIX + "dup1@hotel.com"));
        mockMvc.perform(post("/api/staff").with(user("tester").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(PREFIX + "dup", PREFIX + "dup2@hotel.com")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Username already exists"));
        assertTrue(userRepository.findByUsername(PREFIX + "dup").isPresent());
        assertTrue(userRepository.findByEmail(PREFIX + "dup2@hotel.com").isEmpty(), "No second account is created");
    }

    @Test
    public void usernameIsNotChangedOrNormalisedOnSubmission() throws Exception {
        assertEquals(201, create("MANAGER", PREFIX + "Exact_Case", PREFIX + "case@hotel.com"));
        assertTrue(userRepository.findByUsername(PREFIX + "Exact_Case").isPresent());
    }

    @Test
    public void concurrentCreationOfTheSameUsernameCreatesExactlyOneAccount() throws Exception {
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String email = PREFIX + "race" + i + "@hotel.com";
            Callable<Integer> task = () -> {
                ready.countDown();
                go.await();
                return create("ADMIN", PREFIX + "race", email);
            };
            results.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        int created = 0, conflicts = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 201) created++;
            else if (code == 409) conflicts++;
        }
        pool.shutdown();
        assertEquals(1, created, "Exactly one request wins");
        assertEquals(attempts - 1, conflicts, "Every loser gets a clean 409, never a 500");
        assertEquals(1, userRepository.findAll().stream().filter(u -> u.getUsername().equals(PREFIX + "race")).count());
    }
}
