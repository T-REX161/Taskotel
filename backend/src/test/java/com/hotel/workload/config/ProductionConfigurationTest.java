package com.hotel.workload.config;

import com.hotel.workload.common.DatabaseMigrationRunner;
import com.hotel.workload.common.DemoDataSeeder;
import com.hotel.workload.security.TestAuthorizationController;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.SendGridProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ProductionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    @DisplayName("In 'prod' profile: DemoDataSeeder, TestAuthorizationController, and DatabaseMigrationRunner are NOT registered")
    public void testBeansExcludedInProd() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=",
                        "jwt.expiration=86400000"
                )
                .withUserConfiguration(
                        DemoDataSeeder.class,
                        TestAuthorizationController.class,
                        DatabaseMigrationRunner.class,
                        ProductionSecurityValidator.class
                )
                .withBean(SendGridProperties.class, () -> new SendGridProperties("SG.actual-live-production-key", "notifications@hotelwb.com", "EquiFlow"))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(com.hotel.workload.staff.StaffRepository.class, () -> mock(com.hotel.workload.staff.StaffRepository.class))
                .withBean(com.hotel.workload.staff.DepartmentRepository.class, () -> mock(com.hotel.workload.staff.DepartmentRepository.class))
                .withBean(com.hotel.workload.task.TaskRepository.class, () -> mock(com.hotel.workload.task.TaskRepository.class))
                .withBean(com.hotel.workload.assignment.AssignmentRepository.class, () -> mock(com.hotel.workload.assignment.AssignmentRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DemoDataSeeder.class);
                    assertThat(context).doesNotHaveBean(TestAuthorizationController.class);
                    assertThat(context).doesNotHaveBean(DatabaseMigrationRunner.class);
                    assertThat(context).hasSingleBean(ProductionSecurityValidator.class);
                });
    }

    @Test
    @DisplayName("In 'production' profile alias: DemoDataSeeder, TestAuthorizationController, and DatabaseMigrationRunner are NOT registered")
    public void testBeansExcludedInProductionAlias() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=production",
                        "jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=",
                        "jwt.expiration=86400000"
                )
                .withUserConfiguration(
                        DemoDataSeeder.class,
                        TestAuthorizationController.class,
                        DatabaseMigrationRunner.class,
                        ProductionSecurityValidator.class
                )
                .withBean(SendGridProperties.class, () -> new SendGridProperties("SG.actual-live-production-key", "notifications@hotelwb.com", "EquiFlow"))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(com.hotel.workload.staff.StaffRepository.class, () -> mock(com.hotel.workload.staff.StaffRepository.class))
                .withBean(com.hotel.workload.staff.DepartmentRepository.class, () -> mock(com.hotel.workload.staff.DepartmentRepository.class))
                .withBean(com.hotel.workload.task.TaskRepository.class, () -> mock(com.hotel.workload.task.TaskRepository.class))
                .withBean(com.hotel.workload.assignment.AssignmentRepository.class, () -> mock(com.hotel.workload.assignment.AssignmentRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DemoDataSeeder.class);
                    assertThat(context).doesNotHaveBean(TestAuthorizationController.class);
                    assertThat(context).doesNotHaveBean(DatabaseMigrationRunner.class);
                    assertThat(context).hasSingleBean(ProductionSecurityValidator.class);
                });
    }

    @Test
    @DisplayName("In dev/default profile: DemoDataSeeder, TestAuthorizationController, and DatabaseMigrationRunner ARE registered")
    public void testBeansRegisteredInDevAndTest() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWtleS1tdXN0LWJlLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=",
                        "jwt.expiration=86400000"
                )
                .withUserConfiguration(
                        DemoDataSeeder.class,
                        TestAuthorizationController.class,
                        DatabaseMigrationRunner.class,
                        ProductionSecurityValidator.class,
                        SendGridProperties.class
                )
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(com.hotel.workload.staff.StaffRepository.class, () -> mock(com.hotel.workload.staff.StaffRepository.class))
                .withBean(com.hotel.workload.staff.DepartmentRepository.class, () -> mock(com.hotel.workload.staff.DepartmentRepository.class))
                .withBean(com.hotel.workload.task.TaskRepository.class, () -> mock(com.hotel.workload.task.TaskRepository.class))
                .withBean(com.hotel.workload.assignment.AssignmentRepository.class, () -> mock(com.hotel.workload.assignment.AssignmentRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DemoDataSeeder.class);
                    assertThat(context).hasSingleBean(TestAuthorizationController.class);
                    assertThat(context).hasSingleBean(DatabaseMigrationRunner.class);
                    assertThat(context).doesNotHaveBean(ProductionSecurityValidator.class);
                });
    }

    @Test
    @DisplayName("application-prod.yml uses ddl-auto: validate and disables SQL statement logging")
    public void testApplicationProdProperties() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        ClassPathResource resource = new ClassPathResource("application-prod.yml");
        assertTrue(resource.exists(), "application-prod.yml must exist in classpath");

        List<PropertySource<?>> propertySources = loader.load("application-prod", resource);
        assertFalse(propertySources.isEmpty(), "application-prod.yml must not be empty");

        PropertySource<?> source = propertySources.get(0);
        assertEquals("validate", source.getProperty("spring.jpa.hibernate.ddl-auto"), "Production ddl-auto must be validate");
        assertEquals(false, source.getProperty("spring.jpa.show-sql"), "Production show-sql must be false");
        assertEquals(false, source.getProperty("spring.jpa.properties.hibernate.format_sql"), "Production format_sql must be false");
        assertEquals("${JWT_SECRET}", source.getProperty("jwt.secret"), "Production jwt.secret must be bound to JWT_SECRET");
        assertEquals("${SENDGRID_API_KEY}", source.getProperty("sendgrid.api-key"), "Production sendgrid.api-key must be bound to SENDGRID_API_KEY");
        assertEquals("${SENDGRID_FROM_EMAIL}", source.getProperty("sendgrid.from-email"), "Production sendgrid.from-email must be bound to SENDGRID_FROM_EMAIL");
    }

    @Test
    @DisplayName("application-production.yml uses ddl-auto: validate and disables SQL statement logging")
    public void testApplicationProductionProperties() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        ClassPathResource resource = new ClassPathResource("application-production.yml");
        assertTrue(resource.exists(), "application-production.yml must exist in classpath");

        List<PropertySource<?>> propertySources = loader.load("application-production", resource);
        assertFalse(propertySources.isEmpty(), "application-production.yml must not be empty");

        PropertySource<?> source = propertySources.get(0);
        assertEquals("validate", source.getProperty("spring.jpa.hibernate.ddl-auto"), "Production ddl-auto must be validate");
        assertEquals(false, source.getProperty("spring.jpa.show-sql"), "Production show-sql must be false");
        assertEquals(false, source.getProperty("spring.jpa.properties.hibernate.format_sql"), "Production format_sql must be false");
        assertEquals("${JWT_SECRET}", source.getProperty("jwt.secret"), "Production jwt.secret must be bound to JWT_SECRET");
        assertEquals("${SENDGRID_API_KEY}", source.getProperty("sendgrid.api-key"), "Production sendgrid.api-key must be bound to SENDGRID_API_KEY");
        assertEquals("${SENDGRID_FROM_EMAIL}", source.getProperty("sendgrid.from-email"), "Production sendgrid.from-email must be bound to SENDGRID_FROM_EMAIL");
    }

    @Test
    @DisplayName("Both 'prod' and 'production' configuration profiles define matching settings")
    public void testBothProductionProfilesMatch() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        ClassPathResource prodRes = new ClassPathResource("application-prod.yml");
        PropertySource<?> prodSource = loader.load("prod", prodRes).get(0);

        ClassPathResource productionRes = new ClassPathResource("application-production.yml");
        PropertySource<?> productionSource = loader.load("production", productionRes).get(0);

        assertEquals(prodSource.getProperty("spring.jpa.hibernate.ddl-auto"),
                productionSource.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(prodSource.getProperty("spring.jpa.show-sql"),
                productionSource.getProperty("spring.jpa.show-sql"));
        assertEquals(prodSource.getProperty("spring.jpa.properties.hibernate.format_sql"),
                productionSource.getProperty("spring.jpa.properties.hibernate.format_sql"));
        assertEquals(prodSource.getProperty("jwt.secret"),
                productionSource.getProperty("jwt.secret"));
        assertEquals(prodSource.getProperty("sendgrid.api-key"),
                productionSource.getProperty("sendgrid.api-key"));
        assertEquals(prodSource.getProperty("sendgrid.from-email"),
                productionSource.getProperty("sendgrid.from-email"));
    }

    // ── P4a-1: Flyway is present but disabled unless WB_FLYWAY_ENABLED=true ──

    /** The main application.yml; on the test classpath "application.yml" resolves to the test copy instead. */
    private static PropertySource<?> loadMainApplicationYml() throws Exception {
        FileSystemResource resource = new FileSystemResource("src/main/resources/application.yml");
        assertTrue(resource.exists(), "src/main/resources/application.yml must exist");
        return new YamlPropertySourceLoader().load("application", resource).get(0);
    }

    private static PropertySource<?> loadClasspathYml(String name) throws Exception {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name + ".yml")).get(0);
    }

    /**
     * Resolves properties the way Spring Boot layers them for the given profiles (profile files
     * override the base file), with the machine's real environment variables and system properties
     * removed so a WB_FLYWAY_ENABLED set on this computer cannot influence the result.
     */
    private static StandardEnvironment environmentFor(Map<String, Object> overrides, String... profileFiles) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("overrides", overrides));
        for (String profileFile : profileFiles) {
            env.getPropertySources().addLast(loadClasspathYml(profileFile));
        }
        env.getPropertySources().addLast(loadMainApplicationYml());
        return env;
    }

    @Test
    @DisplayName("application.yml keeps Flyway disabled by default and baselines existing databases at version 2")
    public void testMainConfigurationKeepsFlywayDisabledByDefault() throws Exception {
        PropertySource<?> main = loadMainApplicationYml();
        assertEquals("${WB_FLYWAY_ENABLED:false}", main.getProperty("spring.flyway.enabled"),
                "Flyway must only run when WB_FLYWAY_ENABLED=true is set explicitly");
        assertEquals("classpath:db/migration", main.getProperty("spring.flyway.locations"));
        assertEquals(true, main.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("2", String.valueOf(main.getProperty("spring.flyway.baseline-version")));
    }

    @Test
    @DisplayName("prod and production never baseline automatically and do not override spring.flyway.enabled")
    public void testProductionProfilesNeverBaselineAutomatically() throws Exception {
        for (String name : List.of("application-prod", "application-production")) {
            PropertySource<?> source = loadClasspathYml(name);
            assertEquals(false, source.getProperty("spring.flyway.baseline-on-migrate"),
                    name + ".yml must refuse to baseline a non-empty database without Flyway history");
            assertNull(source.getProperty("spring.flyway.enabled"),
                    name + ".yml must not set spring.flyway.enabled; the WB_FLYWAY_ENABLED switch in application.yml decides");
        }
    }

    @Test
    @DisplayName("Flyway resolves to disabled in default, dev, prod and production unless WB_FLYWAY_ENABLED=true")
    public void testFlywayIsDisabledInEveryProfileUnlessExplicitlyEnabled() throws Exception {
        Map<String, String[]> profiles = Map.of(
                "default", new String[0],
                "dev", new String[0],                                            // no application-dev.yml exists
                "prod", new String[] { "application-prod" },
                "production", new String[] { "application-production", "application-prod" }); // production group includes prod

        for (Map.Entry<String, String[]> profile : profiles.entrySet()) {
            StandardEnvironment byDefault = environmentFor(Map.of(), profile.getValue());
            assertEquals(Boolean.FALSE, byDefault.getProperty("spring.flyway.enabled", Boolean.class),
                    "Flyway must be disabled by default in profile " + profile.getKey());

            StandardEnvironment explicitlyEnabled = environmentFor(Map.of("WB_FLYWAY_ENABLED", "true"), profile.getValue());
            assertEquals(Boolean.TRUE, explicitlyEnabled.getProperty("spring.flyway.enabled", Boolean.class),
                    "WB_FLYWAY_ENABLED=true must be the only switch that enables Flyway in profile " + profile.getKey());

            boolean production = profile.getValue().length > 0;
            assertEquals(!production, byDefault.getProperty("spring.flyway.baseline-on-migrate", Boolean.class),
                    "baseline-on-migrate must be true only outside the production profiles (" + profile.getKey() + ")");
        }
    }

    @Test
    @DisplayName("The test configuration disables Flyway outright")
    public void testTestConfigurationDisablesFlyway() throws Exception {
        PropertySource<?> testConfig = loadClasspathYml("application"); // resolves to src/test/resources/application.yml
        assertEquals(false, testConfig.getProperty("spring.flyway.enabled"),
                "Tests use H2 with create-drop; Flyway must never run there");
    }

    @Test
    @DisplayName("ProductionSecurityValidator throws exception when SendGrid API key is missing or blank")
    public void testProductionValidator_MissingSendGridKey_Fails() {
        SendGridProperties props = new SendGridProperties("", "notifications@hotelwb.com", "EquiFlow");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("SENDGRID_API_KEY is missing or contains an invalid/placeholder test key"));
    }

    @Test
    @DisplayName("ProductionSecurityValidator throws exception when SendGrid API key is a test placeholder")
    public void testProductionValidator_PlaceholderTestKey_Fails() {
        SendGridProperties props = new SendGridProperties("test-sendgrid-api-key", "notifications@hotelwb.com", "EquiFlow");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("SENDGRID_API_KEY is missing or contains an invalid/placeholder test key"));
    }

    @Test
    @DisplayName("ProductionSecurityValidator throws exception when from-email is missing or invalid")
    public void testProductionValidator_MissingFromEmail_Fails() {
        SendGridProperties props = new SendGridProperties("SG.actual-live-api-key", "", "EquiFlow");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("SENDGRID_FROM_EMAIL is missing or invalid"));
    }

    @Test
    @DisplayName("ProductionSecurityValidator succeeds when valid production credentials are provided")
    public void testProductionValidator_ValidCredentials_Succeeds() {
        SendGridProperties props = new SendGridProperties("SG.actual-live-production-key", "noreply@hotelwb.com", "EquiFlow");
        ProductionSecurityValidator validator = new ProductionSecurityValidator(props);

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    @DisplayName("DemoDataSeeder does not seed accounts if 'prod' profile is active")
    public void testDemoDataSeeder_InProd_NeverSeeds() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.count()).thenReturn(0L);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        DemoDataSeeder seeder = new DemoDataSeeder(
                userRepo,
                mock(com.hotel.workload.staff.StaffRepository.class),
                mock(com.hotel.workload.task.TaskRepository.class),
                mock(com.hotel.workload.assignment.AssignmentRepository.class),
                mock(PasswordEncoder.class),
                env,
                mock(com.hotel.workload.staff.DepartmentRepository.class)
        );

        seeder.run();

        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("DemoDataSeeder does not seed accounts if 'production' profile alias is active")
    public void testDemoDataSeeder_InProductionAlias_NeverSeeds() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.count()).thenReturn(0L);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");

        DemoDataSeeder seeder = new DemoDataSeeder(
                userRepo,
                mock(com.hotel.workload.staff.StaffRepository.class),
                mock(com.hotel.workload.task.TaskRepository.class),
                mock(com.hotel.workload.assignment.AssignmentRepository.class),
                mock(PasswordEncoder.class),
                env,
                mock(com.hotel.workload.staff.DepartmentRepository.class)
        );

        seeder.run();

        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("DemoDataSeeder skips seeding if database already has users")
    public void testDemoDataSeeder_ExistingUsers_SkipsSeeding() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.count()).thenReturn(5L);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        DemoDataSeeder seeder = new DemoDataSeeder(
                userRepo,
                mock(com.hotel.workload.staff.StaffRepository.class),
                mock(com.hotel.workload.task.TaskRepository.class),
                mock(com.hotel.workload.assignment.AssignmentRepository.class),
                mock(PasswordEncoder.class),
                env,
                mock(com.hotel.workload.staff.DepartmentRepository.class)
        );

        seeder.run();

        verify(userRepo, never()).save(any());
    }
}
