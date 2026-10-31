package com.hotel.workload.config;

import com.hotel.workload.profile.CloudinaryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * backend/.env loading, checked with Spring Boot's own config-data machinery and the real application.yml. The developer's real
 * backend/.env is never read here: the import paths in a copy of application.yml are pointed at a temporary file instead.
 */
public class EnvFileLoadingTest {

    private static final Path MAIN_CONFIG = Path.of("src/main/resources/application.yml");

    @TempDir
    Path dir;

    // Deliberately not annotated: a @SpringBootConfiguration here would be picked up by other @SpringBootTest classes in this package
    static class NoBeans {}

    /** Starts an application with no beans, using a copy of the real application.yml whose .env imports point into {@code dir}. */
    private ConfigurableApplicationContext start(Path envFile, Map<String, Object> environmentVariables) throws IOException {
        String yaml = Files.readString(MAIN_CONFIG, StandardCharsets.UTF_8)
                .replace("optional:file:.env[.properties]", "optional:file:" + envFile.toAbsolutePath().toString().replace('\\', '/') + "[.properties]")
                .replace("optional:file:backend/.env[.properties]", "optional:file:" + dir.resolve("nowhere/.env").toAbsolutePath().toString().replace('\\', '/') + "[.properties]");
        Path config = dir.resolve("application.yml");
        Files.writeString(config, yaml, StandardCharsets.UTF_8);

        ConfigurableEnvironment environment = new StandardEnvironment();
        // Stand-in for the operating system environment, so precedence can be checked without touching the real one
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        return new SpringApplicationBuilder(NoBeans.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run("--spring.config.location=file:" + config.toAbsolutePath().toString().replace('\\', '/'));
    }

    private static CloudinaryProperties cloudinary(ConfigurableApplicationContext context) {
        return Binder.get(context.getEnvironment()).bind("cloudinary", CloudinaryProperties.class).orElseGet(CloudinaryProperties::new);
    }

    @Test
    @DisplayName("application.yml imports backend/.env (optionally, as a properties file) from the backend folder and from the repository root")
    void mainConfigDeclaresTheImport() throws IOException {
        String yaml = Files.readString(MAIN_CONFIG, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("- optional:file:.env[.properties]"));
        assertTrue(yaml.contains("- optional:file:backend/.env[.properties]"));
        assertTrue(yaml.contains("cloud-name: ${CLOUDINARY_CLOUD_NAME:}"));
        assertTrue(yaml.contains("api-key: ${CLOUDINARY_API_KEY:}"));
        assertTrue(yaml.contains("api-secret: ${CLOUDINARY_API_SECRET:}"));
        assertTrue(yaml.contains("folder: taskotel/profile-pictures"));
    }

    @Test
    @DisplayName("KEY=value lines in .env reach the Cloudinary settings; comments are ignored")
    void envFileValuesAreLoaded() throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # local settings
                CLOUDINARY_CLOUD_NAME=demo-cloud
                CLOUDINARY_API_KEY=123456789012345
                CLOUDINARY_API_SECRET=abc_DEF-123
                """);
        try (ConfigurableApplicationContext context = start(env, Map.of())) {
            CloudinaryProperties properties = cloudinary(context);
            assertTrue(properties.isConfigured());
            assertEquals("demo-cloud", properties.getCloudName());
            assertEquals("123456789012345", properties.getApiKey());
            assertEquals("abc_DEF-123", properties.getApiSecret());
            assertEquals("taskotel/profile-pictures", properties.getFolder());
            assertEquals("5MB", context.getEnvironment().getProperty("spring.servlet.multipart.max-file-size"));
        }
    }

    @Test
    @DisplayName("A .env with the API key's label as the cloud name is caught at startup instead of failing every upload")
    void keyLabelAsCloudNameIsCaught() throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, "CLOUDINARY_CLOUD_NAME=Taskotel Backend\nCLOUDINARY_API_KEY=123456789012345\nCLOUDINARY_API_SECRET=abc_DEF-123\n");
        try (ConfigurableApplicationContext context = start(env, Map.of())) {
            CloudinaryProperties properties = cloudinary(context);
            assertEquals("Taskotel Backend", properties.getCloudName(), "the value is read as written, space included");
            assertFalse(properties.isConfigured());
            assertTrue(properties.invalidSettings().get(0).startsWith("CLOUDINARY_CLOUD_NAME"));
        }
    }

    @Test
    @DisplayName("A real environment variable wins over the same key in .env, and other existing variables still resolve")
    void environmentVariablesTakePrecedence() throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, "CLOUDINARY_CLOUD_NAME=from-file\nCLOUDINARY_API_KEY=111111\nCLOUDINARY_API_SECRET=file-secret\n");
        Map<String, Object> variables = Map.of("CLOUDINARY_CLOUD_NAME", "from-environment", "DB_NAME", "some_db", "SENDGRID_FROM_NAME", "Env Sender");
        try (ConfigurableApplicationContext context = start(env, variables)) {
            assertEquals("from-environment", cloudinary(context).getCloudName());
            assertEquals("111111", cloudinary(context).getApiKey(), "keys not set in the environment still come from the file");
            assertTrue(context.getEnvironment().getProperty("spring.datasource.url").endsWith("/some_db"), "database settings unaffected");
            assertEquals("Env Sender", context.getEnvironment().getProperty("sendgrid.from-name"), "SendGrid settings unaffected");
        }
    }

    @Test
    @DisplayName("Without a .env file the backend still starts; Cloudinary is simply not configured")
    void missingEnvFileIsFine() throws IOException {
        try (ConfigurableApplicationContext context = start(dir.resolve("absent/.env"), Map.of())) {
            CloudinaryProperties properties = cloudinary(context);
            assertFalse(properties.isConfigured());
            assertEquals(List.of("CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET"), properties.missingSettings());
        }
    }

    @Test
    @DisplayName("The placeholder .env from .env.example leaves Cloudinary switched off rather than sending fake credentials")
    void placeholdersAreNotConfigured() throws IOException {
        Path env = dir.resolve(".env");
        Files.copy(Path.of(".env.example"), env);
        try (ConfigurableApplicationContext context = start(env, Map.of())) {
            assertFalse(cloudinary(context).isConfigured());
        }
    }

    @Test
    @DisplayName(".env.example holds placeholders only, and backend/.env is git-ignored")
    void exampleHasPlaceholdersOnlyAndEnvIsIgnored() throws IOException {
        Matcher setting = Pattern.compile("(?m)^\\s*([A-Z0-9_]+)\\s*=\\s*(.*)$").matcher(Files.readString(Path.of(".env.example")));
        int count = 0;
        while (setting.find()) {
            count++;
            assertTrue(setting.group(2).trim().startsWith("your_"), setting.group(1) + " must be a placeholder in .env.example");
        }
        assertEquals(3, count, "the three Cloudinary settings");
        List<String> ignored = Files.readAllLines(Path.of("../.gitignore"));
        assertTrue(ignored.contains("backend/.env") || ignored.contains(".env"), "backend/.env must be ignored by git");
    }
}
