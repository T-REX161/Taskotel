
package com.hotel.workload.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
public class StaffControllerTest {

        @Autowired
        private WebApplicationContext context;

        @Autowired
        private StaffRepository staffRepository;

        @Autowired
        private com.hotel.workload.security.UserRepository userRepository;

        @Autowired
        private com.hotel.workload.assignment.AssignmentRepository assignmentRepository;

        @Autowired
        private com.hotel.workload.task.TaskRepository taskRepository;

        @Autowired
        private jakarta.persistence.EntityManager entityManager;

        private MockMvc mockMvc;

        @BeforeEach
        public void setup() {
                mockMvc = MockMvcBuilders.webAppContextSetup(context)
                                .apply(springSecurity())
                                .build();
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        public void testGetStaffWithAdminRole() throws Exception {
                mockMvc.perform(get("/api/staff"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray());
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        public void testGetStaffWithManagerRole() throws Exception {
                mockMvc.perform(get("/api/staff"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray());
        }

        @Test
        @WithMockUser(roles = "STAFF")
        public void testGetStaffWithStaffRole() throws Exception {
                mockMvc.perform(get("/api/staff"))
                                .andExpect(status().isForbidden());
        }

        @Test
        public void testGetStaffUnauthenticated() throws Exception {
                mockMvc.perform(get("/api/staff"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaffWithAdminRole() throws Exception {
                String json = """
                                {
                                    "fullName": "New Staff",
                                    "email": "new@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "newstaff",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.username").value("newstaff"));
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaffWithManagerRole() throws Exception {
                String json = """
                                {
                                    "fullName": "Manager Staff",
                                    "email": "manager@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "managerstaff",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.username").value("managerstaff"));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        public void testCreateStaffWithStaffRole() throws Exception {
                String json = """
                                {
                                    "fullName": "Should Fail",
                                    "email": "fail@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "failstaff",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isForbidden());
        }

        @Test
        public void testCreateStaffUnauthenticated() throws Exception {
                String json = """
                                {
                                    "fullName": "Should Fail",
                                    "email": "fail@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "failstaff2",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaffValidationFails() throws Exception {
                String json = """
                                {
                                    "fullName": "",
                                    "email": "not-an-email",
                                    "department": null,
                                    "username": ""
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaffUsernameConflict() throws Exception {
                String json1 = """
                                {
                                    "fullName": "Staff 1",
                                    "email": "staff1@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "conflict_user",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                // Create first staff
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json1))
                                .andExpect(status().isCreated());

                // Try to create another staff with the same username
                String json2 = """
                                {
                                    "fullName": "Staff 2",
                                    "email": "staff2@staff.com",
                                    "phone": "123456789",
                                    "department": "HOUSEKEEPING",
                                    "availability": "UNAVAILABLE",
                                    "username": "conflict_user",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json2))
                                .andExpect(status().isConflict());
        }

        private String createStaffHelper(String username, String email) throws Exception {
                String createJson = String.format("""
                                {
                                    "fullName": "Initial Staff",
                                    "email": "%s",
                                    "phone": "123",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "%s",
                                    "temporaryPassword": "TempPass@123"
                                }
                                """, email, username);
                String response = mockMvc
                                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                                .post("/api/staff")
                                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                                .content(createJson))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString();
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.readTree(response).get("id").asText();
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffWithAdminRole() throws Exception {
                String id = createStaffHelper("adminupdate", "adminupdate@staff.com");

                String updateJson = """
                                {
                                    "fullName": "Updated Staff",
                                    "email": "updated_admin@staff.com",
                                    "phone": "987654321",
                                    "department": "HOUSEKEEPING",
                                    "availability": "UNAVAILABLE",
                                    "active": false
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + id)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.name").value("Updated Staff"))
                                .andExpect(jsonPath("$.email").value("updated_admin@staff.com"))
                                .andExpect(jsonPath("$.username").value("adminupdate"))
                                .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffWithManagerRole() throws Exception {
                String id = createStaffHelper("managerupdate", "managerupdate@staff.com");

                String updateJson = """
                                {
                                    "fullName": "Updated By Manager",
                                    "email": "updated_manager@staff.com",
                                    "phone": "987654321",
                                    "department": "MAINTENANCE",
                                    "availability": "UNAVAILABLE",
                                    "active": true
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + id)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.name").value("Updated By Manager"))
                                .andExpect(jsonPath("$.email").value("updated_manager@staff.com"));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffWithStaffRole() throws Exception {
                String updateJson = """
                                {
                                    "fullName": "Updated Staff",
                                    "email": "updated@staff.com",
                                    "phone": "987654321",
                                    "department": "HOUSEKEEPING",
                                    "availability": "UNAVAILABLE",
                                    "active": false
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + java.util.UUID.randomUUID().toString())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isForbidden());
        }

        @Test
        public void testUpdateStaffUnauthenticated() throws Exception {
                String updateJson = """
                                {
                                    "fullName": "Updated Staff",
                                    "email": "updated@staff.com",
                                    "phone": "987654321",
                                    "department": "HOUSEKEEPING",
                                    "availability": "UNAVAILABLE",
                                    "active": false
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + java.util.UUID.randomUUID().toString())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        public void testUpdateStaffNotFound() throws Exception {
                String updateJson = """
                                {
                                    "fullName": "Updated Staff",
                                    "email": "updated@staff.com",
                                    "phone": "987654321",
                                    "department": "HOUSEKEEPING",
                                    "availability": "UNAVAILABLE",
                                    "active": false
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + java.util.UUID.randomUUID().toString())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffValidationFails() throws Exception {
                String id = createStaffHelper("validfail", "validfail@staff.com");

                String updateJson = """
                                {
                                    "fullName": "",
                                    "email": "invalid-email",
                                    "phone": "987654321",
                                    "department": null,
                                    "availability": null,
                                    "active": false
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/staff/" + id)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateJson))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateStaffWithAdminRole() throws Exception {
                String id = createStaffHelper("admin_deact", "admin_deact@staff.com");

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                String loginJson = """
                                {
                                    "username": "admin_deact",
                                    "password": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(loginJson))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateStaffWithManagerRole() throws Exception {
                String id = createStaffHelper("manager_deact", "manager_deact@staff.com");

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                String loginJson = """
                                {
                                    "username": "manager_deact",
                                    "password": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(loginJson))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateStaffWithStaffRole() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/deactivate"))
                                .andExpect(status().isForbidden());
        }

        @Test
        public void testDeactivateStaffUnauthenticated() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/deactivate"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        public void testDeactivateStaffNotFound() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/deactivate"))
                                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateAlreadyInactiveStaff() throws Exception {
                String id = createStaffHelper("already_deact", "already_deact@staff.com");

                // First deactivation
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                // Second deactivation should be handled safely (e.g. return 200 OK without
                // errors)
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_WeakPassword_TooShort_ReturnsBadRequest() throws Exception {
                String json = """
                                {
                                    "fullName": "Short Pass Staff",
                                    "email": "shortpass@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "shortpass_staff",
                                    "temporaryPassword": "Pass@1"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 8 characters")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_WeakPassword_MissingUppercase_ReturnsBadRequest() throws Exception {
                String json = """
                                {
                                    "fullName": "No Upper Staff",
                                    "email": "noupper@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "noupper_staff",
                                    "temporaryPassword": "pass@1234"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("uppercase letter")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_WeakPassword_MissingLowercase_ReturnsBadRequest() throws Exception {
                String json = """
                                {
                                    "fullName": "No Lower Staff",
                                    "email": "nolower@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "nolower_staff",
                                    "temporaryPassword": "PASS@1234"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lowercase letter")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_WeakPassword_MissingDigit_ReturnsBadRequest() throws Exception {
                String json = """
                                {
                                    "fullName": "No Digit Staff",
                                    "email": "nodigit@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "nodigit_staff",
                                    "temporaryPassword": "Password@"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("digit")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_WeakPassword_MissingSpecialChar_ReturnsBadRequest() throws Exception {
                String json = """
                                {
                                    "fullName": "No Special Staff",
                                    "email": "nospecial@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "nospecial_staff",
                                    "temporaryPassword": "Password123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("special character")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testCreateStaff_ValidPassword_ReturnsCreated() throws Exception {
                String json = """
                                {
                                    "fullName": "Strong Pass Staff",
                                    "email": "strongpass@staff.com",
                                    "phone": "123456789",
                                    "department": "FRONT_DESK",
                                    "availability": "AVAILABLE",
                                    "username": "strongpass_staff",
                                    "temporaryPassword": "SecurePass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.username").value("strongpass_staff"));
        }

        private Staff createTestStaff(String username, String email, boolean active) {
                com.hotel.workload.security.User user = new com.hotel.workload.security.User();
                user.setUsername(username);
                user.setEmail(email);
                user.setPassword("password123");
                user.setRole(com.hotel.workload.security.Role.STAFF);
                user.setActive(active);
                user = userRepository.save(user);

                Staff staff = new Staff();
                staff.setUser(user);
                staff.setName(username + " Full");
                staff.setEmail(email);
                staff.setDepartment(Department.FRONT_DESK);
                staff.setAvailability(Availability.AVAILABLE);
                staff.setActive(active);
                return staffRepository.save(staff);
        }

        private com.hotel.workload.task.Task createTestTask(String title, com.hotel.workload.task.TaskStatus status, com.hotel.workload.security.User creator) {
                com.hotel.workload.task.Task task = new com.hotel.workload.task.Task();
                task.setTitle(title);
                task.setPriority(com.hotel.workload.task.Priority.MEDIUM);
                task.setStatus(status);
                task.setDepartment(Department.FRONT_DESK);
                task.setCreatedBy(creator);
                return taskRepository.save(task);
        }

        private com.hotel.workload.assignment.Assignment createTestAssignment(com.hotel.workload.task.Task task, Staff staff) {
                com.hotel.workload.assignment.Assignment assignment = new com.hotel.workload.assignment.Assignment();
                assignment.setTask(task);
                assignment.setStaff(staff);
                return assignmentRepository.save(assignment);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testAdminCanRemoveInactiveStaff() throws Exception {
                Staff staff = createTestStaff("inactive_to_remove", "inactiveremove@hotel.com", false);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isNoContent());

                org.junit.jupiter.api.Assertions.assertFalse(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testAdminCannotRemoveActiveStaff() throws Exception {
                Staff staff = createTestStaff("active_cannot_remove", "activeremove@hotel.com", true);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isBadRequest());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testManagerCannotRemoveStaff() throws Exception {
                Staff staff = createTestStaff("mgr_forbidden_staff", "mgrforbidden@hotel.com", false);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isForbidden());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @org.springframework.transaction.annotation.Transactional
        public void testStaffRoleCannotRemoveStaff() throws Exception {
                Staff staff = createTestStaff("staff_forbidden_target", "stafftarget@hotel.com", false);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isForbidden());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @org.springframework.transaction.annotation.Transactional
        public void testUnauthenticatedCannotRemoveStaff() throws Exception {
                Staff staff = createTestStaff("unauth_target", "unauth@hotel.com", false);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isUnauthorized());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testAdminCannotRemoveInactiveStaffWithActiveAssignment() throws Exception {
                Staff staff = createTestStaff("inactive_with_active_task", "inactivetask@hotel.com", false);
                com.hotel.workload.task.Task task = createTestTask("Active Assigned Task", com.hotel.workload.task.TaskStatus.ASSIGNED, staff.getUser());
                createTestAssignment(task, staff);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isConflict());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testAdminCannotRemoveInactiveStaffWithHistoricalAssignment() throws Exception {
                Staff staff = createTestStaff("inactive_with_history", "inactivehistory@hotel.com", false);
                com.hotel.workload.task.Task task = createTestTask("Completed Task", com.hotel.workload.task.TaskStatus.COMPLETED, staff.getUser());
                createTestAssignment(task, staff);

                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(status().isConflict());

                org.junit.jupiter.api.Assertions.assertTrue(staffRepository.existsById(staff.getId()));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testRemoveStaffEndpointMatchesFrontendDeleteMappingAndIsNotMethodNotAllowed() throws Exception {
                Staff staff = createTestStaff("regression_delete_target", "regressiondelete@hotel.com", false);

                // Exact URL and method used by frontend removeStaff(id): DELETE /api/staff/{id}
                mockMvc.perform(delete("/api/staff/" + staff.getId()))
                                .andExpect(result -> {
                                        org.junit.jupiter.api.Assertions.assertNotEquals(
                                                org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED.value(),
                                                result.getResponse().getStatus(),
                                                "DELETE /api/staff/{id} must be mapped and must not return 405 Method Not Allowed"
                                        );
                                })
                                .andExpect(status().isNoContent());

                // Verifies that unsupported methods on /api/staff/{id} (e.g. POST) correctly return 405 Method Not Allowed
                mockMvc.perform(post("/api/staff/" + staff.getId()))
                                .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testActivateStaffWithAdminRole() throws Exception {
                String id = createStaffHelper("admin_act", "admin_act@staff.com");
                java.util.UUID staffUuid = java.util.UUID.fromString(id);

                // Deactivate first
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                com.hotel.workload.security.User userBefore = userRepository.findByUsername("admin_act").orElseThrow();
                org.junit.jupiter.api.Assertions.assertFalse(userBefore.isActive());

                // Activate with Admin
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/activate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true));

                // Verify both Staff and User are active in database
                Staff activatedStaff = staffRepository.findById(staffUuid).orElseThrow();
                com.hotel.workload.security.User userAfter = userRepository.findByUsername("admin_act").orElseThrow();
                org.junit.jupiter.api.Assertions.assertTrue(activatedStaff.isActive());
                org.junit.jupiter.api.Assertions.assertTrue(userAfter.isActive());

                // Set emailVerified=true and credentialsSetupRequired=false to test direct login
                userAfter.setEmailVerified(true);
                userAfter.setCredentialsSetupRequired(false);
                userRepository.save(userAfter);

                String loginJson = """
                                {
                                    "username": "admin_act",
                                    "password": "TempPass@123"
                                }
                                """;
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(loginJson))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.token").isString());
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testActivateStaffWithManagerRole() throws Exception {
                String id = createStaffHelper("manager_act", "manager_act@staff.com");

                // Deactivate first
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                // Activate with Manager
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/activate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @org.springframework.transaction.annotation.Transactional
        public void testActivateStaffWithStaffRole() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/activate"))
                                .andExpect(status().isForbidden());
        }

        @Test
        public void testActivateStaffUnauthenticated() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/activate"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        public void testActivateStaffNotFound() throws Exception {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + java.util.UUID.randomUUID().toString() + "/activate"))
                                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testActivateAlreadyActiveStaff_Idempotent() throws Exception {
                String id = createStaffHelper("already_act", "already_act@staff.com");

                // Staff is active initially; repeated activation should be idempotent
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/activate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true));

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/activate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testStaffAndUserStatusConsistencyInDatabase() throws Exception {
                String id = createStaffHelper("consistency_staff", "consistency@staff.com");
                java.util.UUID staffUuid = java.util.UUID.fromString(id);

                Staff staff = staffRepository.findById(staffUuid).orElseThrow();
                com.hotel.workload.security.User user = staff.getUser();
                org.junit.jupiter.api.Assertions.assertTrue(staff.isActive());
                org.junit.jupiter.api.Assertions.assertTrue(user.isActive());

                // Deactivate
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/deactivate"))
                                .andExpect(status().isOk());

                Staff deactivatedStaff = staffRepository.findById(staffUuid).orElseThrow();
                com.hotel.workload.security.User deactivatedUser = userRepository.findById(user.getId()).orElseThrow();
                org.junit.jupiter.api.Assertions.assertFalse(deactivatedStaff.isActive());
                org.junit.jupiter.api.Assertions.assertFalse(deactivatedUser.isActive());
                org.junit.jupiter.api.Assertions.assertEquals(deactivatedStaff.isActive(), deactivatedUser.isActive());

                // Activate
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + id + "/activate"))
                                .andExpect(status().isOk());

                Staff reactivatedStaff = staffRepository.findById(staffUuid).orElseThrow();
                com.hotel.workload.security.User reactivatedUser = userRepository.findById(user.getId()).orElseThrow();
                org.junit.jupiter.api.Assertions.assertTrue(reactivatedStaff.isActive());
                org.junit.jupiter.api.Assertions.assertTrue(reactivatedUser.isActive());
                org.junit.jupiter.api.Assertions.assertEquals(reactivatedStaff.isActive(), reactivatedUser.isActive());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testReactivationPreservesAvailabilityAndTaskAssignmentEligibility() throws Exception {
                Staff staff = createTestStaff("reactivate_unavail", "reactivateunavail@hotel.com", false);
                staff.setAvailability(Availability.UNAVAILABLE);
                staff = staffRepository.save(staff);

                // Activate
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + staff.getId() + "/activate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true))
                                .andExpect(jsonPath("$.availability").value("UNAVAILABLE"));

                Staff savedStaff = staffRepository.findById(staff.getId()).orElseThrow();
                org.junit.jupiter.api.Assertions.assertTrue(savedStaff.isActive());
                org.junit.jupiter.api.Assertions.assertEquals(Availability.UNAVAILABLE, savedStaff.getAvailability());

                // Task assignment to unavailable staff must fail
                com.hotel.workload.task.Task task = createTestTask("Task For Reactivated", com.hotel.workload.task.TaskStatus.PENDING, savedStaff.getUser());
                String assignJson = String.format("""
                                {
                                    "taskId": "%s",
                                    "staffId": "%s"
                                }
                                """, task.getId(), savedStaff.getId());

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/assignments")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(assignJson))
                                .andExpect(status().isBadRequest());
        }

        // ── P1: staff with active task assignments cannot be deactivated ──

        private static final String ACTIVE_ASSIGNMENTS_MESSAGE =
                        "Cannot deactivate staff member with active task assignments. Please reassign or complete their active tasks first.";

        private Staff createStaffWithAssignedTask(String username, boolean active, com.hotel.workload.task.TaskStatus taskStatus) {
                Staff staff = createTestStaff(username, username + "@hotel.com", active);
                com.hotel.workload.task.Task task = createTestTask(username + " task", taskStatus, staff.getUser());
                createTestAssignment(task, staff);
                entityManager.flush();
                return staff;
        }

        private String updateStaffJson(Staff staff, String phone, String availability, boolean active) {
                return String.format("""
                                {
                                    "fullName": "%s",
                                    "email": "%s",
                                    "phone": "%s",
                                    "department": "%s",
                                    "availability": "%s",
                                    "active": %s
                                }
                                """, staff.getName(), staff.getEmail(), phone, staff.getDepartment(), availability, active);
        }

        /**
         * Writes any pending changes, then re-reads staff and linked login from the database,
         * bypassing the persistence context. Flushing first means an in-memory change made by a
         * rejected request would still be detected.
         */
        private Staff reloadStaff(java.util.UUID staffId) {
                entityManager.flush();
                entityManager.clear();
                return staffRepository.findById(staffId).orElseThrow();
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = com.hotel.workload.task.TaskStatus.class, names = { "PENDING", "ASSIGNED", "IN_PROGRESS" })
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateStaffWithActiveAssignmentReturns409AndKeepsStaffAndLoginActive(com.hotel.workload.task.TaskStatus taskStatus) throws Exception {
                Staff staff = createStaffWithAssignedTask("deact_busy_" + taskStatus.name().toLowerCase(), true, taskStatus);

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + staff.getId() + "/deactivate"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value(ACTIVE_ASSIGNMENTS_MESSAGE));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertTrue(reloaded.isActive(), "Rejected deactivation must leave the staff record active");
                org.junit.jupiter.api.Assertions.assertTrue(reloaded.getUser().isActive(), "Rejected deactivation must leave the linked login active");
        }

        @Test
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateStaffWithOnlyCompletedOrCancelledAssignmentsSucceeds() throws Exception {
                Staff staff = createStaffWithAssignedTask("deact_history", true, com.hotel.workload.task.TaskStatus.COMPLETED);
                createTestAssignment(createTestTask("deact_history cancelled task", com.hotel.workload.task.TaskStatus.CANCELLED, staff.getUser()), staff);
                entityManager.flush();

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + staff.getId() + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.isActive());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.getUser().isActive());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testDeactivateAlreadyInactiveStaffWithLegacyActiveAssignmentIsIdempotent() throws Exception {
                // Mirrors existing records where staff were deactivated before this rule existed
                Staff staff = createStaffWithAssignedTask("deact_legacy", false, com.hotel.workload.task.TaskStatus.ASSIGNED);

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/staff/" + staff.getId() + "/deactivate"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.isActive());
                org.junit.jupiter.api.Assertions.assertEquals(1, assignmentRepository.findByStaffId(staff.getId()).size(), "Existing assignment must be left untouched");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = com.hotel.workload.task.TaskStatus.class, names = { "PENDING", "ASSIGNED", "IN_PROGRESS" })
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffToInactiveWithActiveAssignmentReturns409AndLeavesRecordUnchanged(com.hotel.workload.task.TaskStatus taskStatus) throws Exception {
                Staff staff = createStaffWithAssignedTask("upd_busy_" + taskStatus.name().toLowerCase(), true, taskStatus);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateStaffJson(staff, "555-0100", "AVAILABLE", false)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value(ACTIVE_ASSIGNMENTS_MESSAGE));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertTrue(reloaded.isActive(), "Rejected update must leave the staff record active");
                org.junit.jupiter.api.Assertions.assertTrue(reloaded.getUser().isActive(), "Rejected update must leave the linked login active");
                org.junit.jupiter.api.Assertions.assertNull(reloaded.getPhone(), "Rejected update must not apply any other field changes");
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateActiveStaffWithActiveAssignmentCanChangeOtherFieldsAndBecomeUnavailable() throws Exception {
                Staff staff = createStaffWithAssignedTask("upd_busy_fields", true, com.hotel.workload.task.TaskStatus.ASSIGNED);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateStaffJson(staff, "555-0101", "UNAVAILABLE", true)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(true));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertTrue(reloaded.isActive());
                org.junit.jupiter.api.Assertions.assertEquals("555-0101", reloaded.getPhone());
                org.junit.jupiter.api.Assertions.assertEquals(Availability.UNAVAILABLE, reloaded.getAvailability());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateStaffToInactiveWithOnlyCompletedAssignmentSucceeds() throws Exception {
                Staff staff = createStaffWithAssignedTask("upd_history", true, com.hotel.workload.task.TaskStatus.COMPLETED);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateStaffJson(staff, "555-0102", "AVAILABLE", false)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.isActive());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.getUser().isActive());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testUpdateAlreadyInactiveStaffWithLegacyActiveAssignmentSucceeds() throws Exception {
                Staff staff = createStaffWithAssignedTask("upd_legacy", false, com.hotel.workload.task.TaskStatus.ASSIGNED);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(updateStaffJson(staff, "555-0103", "AVAILABLE", false)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.active").value(false));

                Staff reloaded = reloadStaff(staff.getId());
                org.junit.jupiter.api.Assertions.assertFalse(reloaded.isActive());
                org.junit.jupiter.api.Assertions.assertEquals("555-0103", reloaded.getPhone());
        }

        // ── P2: staff cannot move away from the department of their active tasks ──

        private String departmentChangeJson(Staff staff, String email, Department department, boolean active) {
                return String.format("""
                                {
                                    "fullName": "%s",
                                    "email": "%s",
                                    "phone": "555-0200",
                                    "department": "%s",
                                    "availability": "AVAILABLE",
                                    "active": %s
                                }
                                """, staff.getName(), email, department, active);
        }

        private com.hotel.workload.task.Task assignTaskInDepartment(Staff staff, String title, Department department, com.hotel.workload.task.TaskStatus status) {
                com.hotel.workload.task.Task task = createTestTask(title, status, staff.getUser());
                task.setDepartment(department);
                task = taskRepository.save(task);
                createTestAssignment(task, staff);
                entityManager.flush();
                return task;
        }

        /** Snapshot of the fields a rejected update must not change, captured before the request is sent. */
        private record StaffSnapshot(java.util.UUID id, String email, Department department, boolean active) {
                static StaffSnapshot of(Staff staff) {
                        return new StaffSnapshot(staff.getId(), staff.getEmail(), staff.getDepartment(), staff.isActive());
                }
        }

        private void assertStaffAndLoginUnchanged(StaffSnapshot before) {
                Staff reloaded = reloadStaff(before.id());
                org.junit.jupiter.api.Assertions.assertEquals(before.department(), reloaded.getDepartment(), "Rejected update must not change the department");
                org.junit.jupiter.api.Assertions.assertEquals(before.email(), reloaded.getEmail(), "Rejected update must not change the staff email");
                org.junit.jupiter.api.Assertions.assertNull(reloaded.getPhone(), "Rejected update must not change any other staff field");
                org.junit.jupiter.api.Assertions.assertEquals(before.active(), reloaded.isActive(), "Rejected update must not change the staff active flag");
                org.junit.jupiter.api.Assertions.assertEquals(before.email(), reloaded.getUser().getEmail(), "Rejected update must not change the linked login email");
                org.junit.jupiter.api.Assertions.assertEquals(before.active(), reloaded.getUser().isActive(), "Rejected update must not change the linked login active flag");
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = com.hotel.workload.task.TaskStatus.class, names = { "PENDING", "ASSIGNED", "IN_PROGRESS" })
        @WithMockUser(roles = "MANAGER")
        @org.springframework.transaction.annotation.Transactional
        public void testChangingDepartmentOfStaffWithActiveTaskReturns409AndLeavesStaffAndLoginUnchanged(com.hotel.workload.task.TaskStatus taskStatus) throws Exception {
                String username = "p2_staff_" + taskStatus.name().toLowerCase();
                Staff staff = createStaffWithAssignedTask(username, true, taskStatus); // FRONT_DESK staff, FRONT_DESK task
                StaffSnapshot before = StaffSnapshot.of(staff);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, "changed_" + username + "@hotel.com", Department.HOUSEKEEPING, true)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value(
                                                "Cannot move " + username + " Full to Housekeeping because they have active tasks in another department: "
                                                                + username + " task (Front Desk). Reassign or complete those tasks first."));

                assertStaffAndLoginUnchanged(before);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testChangingDepartmentOfStaffWithOnlyCompletedOrCancelledTasksSucceeds() throws Exception {
                Staff staff = createStaffWithAssignedTask("p2_staff_history", true, com.hotel.workload.task.TaskStatus.COMPLETED);
                assignTaskInDepartment(staff, "p2_staff_history cancelled", Department.FRONT_DESK, com.hotel.workload.task.TaskStatus.CANCELLED);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, staff.getEmail(), Department.HOUSEKEEPING, true)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.department").value("HOUSEKEEPING"));

                org.junit.jupiter.api.Assertions.assertEquals(Department.HOUSEKEEPING, reloadStaff(staff.getId()).getDepartment());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testChangingDepartmentOfStaffWithoutAssignmentsSucceeds() throws Exception {
                Staff staff = createTestStaff("p2_staff_free", "p2_staff_free@hotel.com", true);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, staff.getEmail(), Department.SECURITY, true)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.department").value("SECURITY"));

                org.junit.jupiter.api.Assertions.assertEquals(Department.SECURITY, reloadStaff(staff.getId()).getDepartment());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testMovingStaffIntoTheDepartmentOfTheirMismatchedTaskSucceeds() throws Exception {
                // Mirrors an existing record where staff hold an active task from another department
                Staff staff = createTestStaff("p2_staff_repair", "p2_staff_repair@hotel.com", true); // FRONT_DESK
                assignTaskInDepartment(staff, "p2 legacy housekeeping task", Department.HOUSEKEEPING, com.hotel.workload.task.TaskStatus.ASSIGNED);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, staff.getEmail(), Department.HOUSEKEEPING, true)))
                                .andExpect(status().isOk());

                org.junit.jupiter.api.Assertions.assertEquals(Department.HOUSEKEEPING, reloadStaff(staff.getId()).getDepartment());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testMovingStaffWithMismatchedTaskToAThirdDepartmentReturns409() throws Exception {
                Staff staff = createTestStaff("p2_staff_third", "p2_staff_third@hotel.com", true); // FRONT_DESK
                assignTaskInDepartment(staff, "p2 legacy task", Department.HOUSEKEEPING, com.hotel.workload.task.TaskStatus.ASSIGNED);
                StaffSnapshot before = StaffSnapshot.of(staff);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, "changed_third@hotel.com", Department.SECURITY, true)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("p2 legacy task (Housekeeping)")));

                assertStaffAndLoginUnchanged(before);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testConflictMessageListsOnlyTasksOutsideTheNewDepartment() throws Exception {
                Staff staff = createTestStaff("p2_staff_mixed", "p2_staff_mixed@hotel.com", true); // FRONT_DESK
                assignTaskInDepartment(staff, "front desk task", Department.FRONT_DESK, com.hotel.workload.task.TaskStatus.ASSIGNED);
                assignTaskInDepartment(staff, "housekeeping task", Department.HOUSEKEEPING, com.hotel.workload.task.TaskStatus.IN_PROGRESS);
                StaffSnapshot before = StaffSnapshot.of(staff);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, staff.getEmail(), Department.HOUSEKEEPING, true)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value(
                                                "Cannot move p2_staff_mixed Full to Housekeeping because they have active tasks in another department: "
                                                                + "front desk task (Front Desk). Reassign or complete those tasks first."));

                assertStaffAndLoginUnchanged(before);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @org.springframework.transaction.annotation.Transactional
        public void testChangingDepartmentOfInactiveStaffWithActiveTaskReturns409() throws Exception {
                // Mirrors existing inactive staff who still hold an active task
                Staff staff = createStaffWithAssignedTask("p2_staff_inactive", false, com.hotel.workload.task.TaskStatus.ASSIGNED);
                StaffSnapshot before = StaffSnapshot.of(staff);

                mockMvc.perform(put("/api/staff/" + staff.getId())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(departmentChangeJson(staff, staff.getEmail(), Department.SECURITY, false)))
                                .andExpect(status().isConflict());

                assertStaffAndLoginUnchanged(before);
        }
}
