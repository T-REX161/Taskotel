package com.hotel.workload.config;

import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("production")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:prodaliastestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V1__initial_schema.sql,classpath:db/migration/V2__add_credentials_setup_required.sql,classpath:db/h2-departments-table.sql,classpath:db/h2-support-assignments-table.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.format_sql=false",
        "jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=",
        "jwt.expiration=86400000",
        "sendgrid.api-key=SG.live-production-sendgrid-api-key-test",
        "sendgrid.from-email=noreply@hotelwb.com",
        "sendgrid.from-name=EquiFlow"
})
public class ProductionAliasEndToEndIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("In 'production' profile alias: DemoDataSeeder and DatabaseMigrationRunner are not registered and demo data is not seeded")
    public void testDemoDataNotSeededInProductionAlias() {
        assertFalse(applicationContext.containsBean("demoDataSeeder"),
                "DemoDataSeeder must not be registered as a Spring bean in production alias");
        assertFalse(applicationContext.containsBean("databaseMigrationRunner"),
                "DatabaseMigrationRunner must not be registered as a Spring bean in production alias");

        // Verify that default demo accounts (admin, manager, rahul) do not exist
        assertFalse(userRepository.findByUsername("admin").isPresent(),
                "Default demo account 'admin' must not be seeded in production alias");
        assertFalse(userRepository.findByUsername("manager").isPresent(),
                "Default demo account 'manager' must not be seeded in production alias");
        assertFalse(userRepository.findByUsername("rahul").isPresent(),
                "Default demo account 'rahul' must not be seeded in production alias");
    }

    @Test
    @DisplayName("In 'production' profile alias: TestAuthorizationController is not registered")
    public void testTestAuthorizationControllerNotRegistered() {
        assertFalse(applicationContext.containsBean("testAuthorizationController"),
                "TestAuthorizationController must not be registered in production alias");
    }

    @Test
    @DisplayName("In 'production' profile alias: ProductionSecurityValidator is registered")
    public void testProductionSecurityValidatorIsRegistered() {
        assertTrue(applicationContext.containsBean("productionSecurityValidator"),
                "ProductionSecurityValidator must be registered in production alias");
    }

    @Test
    @DisplayName("In 'production' profile alias: /api/test/** endpoints are inaccessible and denied by security")
    public void testTestEndpointsInaccessibleInProductionAlias() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // 1. Unauthenticated request to /api/test/admin must be rejected (401 Unauthorized)
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isUnauthorized());

        // 2. Unauthenticated request to /api/test/manager must be rejected (401 Unauthorized)
        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isUnauthorized());

        // 3. Unauthenticated request to /api/test/staff must be rejected (401 Unauthorized)
        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("In 'production' profile alias: even authenticated ADMIN cannot access /api/test/admin (denied by security)")
    public void testAdminCannotAccessTestEndpointsInProductionAlias() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Even with ROLE_ADMIN, /api/test/admin must be denied with 403 Forbidden
        mockMvc.perform(get("/api/test/admin"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/test/manager"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/test/staff"))
                .andExpect(status().isForbidden());
    }
}
