package com.hotel.workload.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
public class TestAuthorizationControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // ADMIN endpoint tests
    @Test
    @WithMockUser(roles = "ADMIN")
    public void testAdminEndpointWithAdminRole() throws Exception {
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testAdminEndpointWithManagerRole() throws Exception {
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testAdminEndpointWithStaffRole() throws Exception {
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isForbidden());
    }

    // MANAGER endpoint tests
    @Test
    @WithMockUser(roles = "ADMIN")
    public void testManagerEndpointWithAdminRole() throws Exception {
        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testManagerEndpointWithManagerRole() throws Exception {
        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testManagerEndpointWithStaffRole() throws Exception {
        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isForbidden());
    }

    // STAFF endpoint tests
    @Test
    @WithMockUser(roles = "ADMIN")
    public void testStaffEndpointWithAdminRole() throws Exception {
        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    public void testStaffEndpointWithManagerRole() throws Exception {
        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    public void testStaffEndpointWithStaffRole() throws Exception {
        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isOk());
    }

    // Unauthenticated request test
    @Test
    public void testUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isUnauthorized());
    }
}
