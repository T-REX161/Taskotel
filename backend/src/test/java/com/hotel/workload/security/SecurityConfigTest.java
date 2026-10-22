package com.hotel.workload.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SecurityConfigTest {

    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    public void setup() {
        jwtUtil = mock(JwtUtil.class);
        filter = new JwtAuthenticationFilter(jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @Test
    public void testValidJwtAuthenticatesRequest() throws Exception {
        String token = "valid-token";
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.extractUsername(token)).thenReturn("admin");
        when(jwtUtil.extractRole(token)).thenReturn("ADMIN");

        filter.doFilter(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        assertEquals("[ROLE_ADMIN]", SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString());
    }

    @Test
    public void testMissingJwtIsUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void testInvalidJwtIsRejected() throws Exception {
        String token = "invalid-token";
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtUtil.validateToken(token)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    
    @Test
    public void testExpiredJwtIsRejected() throws Exception {
        String token = "expired-token";
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtUtil.validateToken(token)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    public void testDeactivatedUserJwtIsRejected() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filterWithRepo = new JwtAuthenticationFilter(jwtUtil, userRepository);

        String token = "valid-token-deactivated-user";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        User deactivatedUser = new User();
        deactivatedUser.setUsername("deactivated_user");
        deactivatedUser.setRole(Role.STAFF);
        deactivatedUser.setActive(false);

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.extractUsername(token)).thenReturn("deactivated_user");
        when(jwtUtil.extractRole(token)).thenReturn("STAFF");
        when(userRepository.findByUsername("deactivated_user")).thenReturn(java.util.Optional.of(deactivatedUser));

        filterWithRepo.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "Deactivated user must not be authenticated");
    }

    @Test
    public void testDeletedUserJwtIsRejected() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filterWithRepo = new JwtAuthenticationFilter(jwtUtil, userRepository);

        String token = "valid-token-deleted-user";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.extractUsername(token)).thenReturn("deleted_user");
        when(jwtUtil.extractRole(token)).thenReturn("STAFF");
        when(userRepository.findByUsername("deleted_user")).thenReturn(java.util.Optional.empty());

        filterWithRepo.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "Deleted/non-existent user must not be authenticated");
    }

    @Test
    public void testActiveUserWithUserRepositoryIsAuthenticated() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filterWithRepo = new JwtAuthenticationFilter(jwtUtil, userRepository);

        String token = "valid-token-active-user";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        User activeUser = new User();
        activeUser.setUsername("active_user");
        activeUser.setRole(Role.STAFF);
        activeUser.setActive(true);

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.extractUsername(token)).thenReturn("active_user");
        when(jwtUtil.extractRole(token)).thenReturn("STAFF");
        when(userRepository.findByUsername("active_user")).thenReturn(java.util.Optional.of(activeUser));

        filterWithRepo.doFilter(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "Active user must be authenticated");
        assertEquals("active_user", SecurityContextHolder.getContext().getAuthentication().getName());
        assertEquals("[ROLE_STAFF]", SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString());
    }
}
