package com.hotel.workload.staff;

import com.hotel.workload.notification.EmailContent;
import com.hotel.workload.notification.NotificationEmailTemplates;
import com.hotel.workload.verification.EmailService;
import com.hotel.workload.verification.SendGridEmailService;
import com.hotel.workload.verification.SendGridProperties;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the links in emails point. The account invitation email is the only email with a link (its login button and the same address
 * written out below it). The address comes from taskotel.frontend-base-url: FRONTEND_BASE_URL if set, otherwise the production profiles
 * default to the production address (defined once in application.yml) and everything else to local development. The real configuration files
 * are loaded and their placeholders resolved exactly as Spring does, then the real email templates and the SendGrid path are exercised.
 */
public class FrontendBaseUrlConfigTest {

    private static final String PRODUCTION = "https://taskotel.vercel.app";
    private static final String LOCAL = "http://localhost:3000";
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** The value of taskotel.frontend-base-url with the given profile file (null = none) and environment variables, highest precedence first. */
    static String baseUrl(String profileFile, Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new org.springframework.core.env.MapPropertySource("environment", new HashMap<>(environment)));
        if (profileFile != null) {
            for (PropertySource<?> p : new YamlPropertySourceLoader().load(profileFile, new FileSystemResource(RESOURCES.resolve(profileFile)))) sources.addLast(p);
        }
        for (PropertySource<?> p : new YamlPropertySourceLoader().load("application.yml", new FileSystemResource(RESOURCES.resolve("application.yml")))) sources.addLast(p);
        return new PropertySourcesPropertyResolver(sources).getProperty("taskotel.frontend-base-url");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    @Test
    @DisplayName("Production (prod and production profiles), FRONTEND_BASE_URL not set: the production address, never localhost")
    public void productionDefault() throws IOException {
        for (String profile : List.of("application-prod.yml", "application-production.yml")) {
            String base = baseUrl(profile, Map.of());
            assertEquals(PRODUCTION, base, profile);
            assertEquals(PRODUCTION + "/login", AccountInvitationService.loginUrlFor(base), profile);
        }
    }

    @Test
    @DisplayName("Local development (no production profile): still the local login address")
    public void localDevelopmentDefault() throws IOException {
        String base = baseUrl(null, Map.of());
        assertEquals(LOCAL, base);
        assertEquals(LOCAL + "/login", AccountInvitationService.loginUrlFor(base));
    }

    @Test
    @DisplayName("FRONTEND_BASE_URL still overrides both: production can point elsewhere, a developer can use another local port")
    public void environmentOverrides() throws IOException {
        Map<String, Object> env = Map.of("FRONTEND_BASE_URL", "https://staging.example.org");
        assertEquals("https://staging.example.org", baseUrl("application-prod.yml", env));
        assertEquals("https://staging.example.org", baseUrl("application-production.yml", env));
        assertEquals("http://localhost:5173", baseUrl(null, Map.of("FRONTEND_BASE_URL", "http://localhost:5173")));
    }

    @Test
    @DisplayName("A production invitation email: the button and the written-out fallback link both use https://taskotel.vercel.app/login, and no localhost anywhere")
    public void productionInvitationEmail() throws IOException {
        String loginUrl = AccountInvitationService.loginUrlFor(baseUrl("application-prod.yml", Map.of()));
        EmailContent email = NotificationEmailTemplates.accountInvitation("mia.rao", "Tmp@Pass1234xYz", loginUrl);
        String link = PRODUCTION + "/login";

        assertTrue(email.html().contains("<a href=\"" + link + "\""), "the button links to the production login page");
        assertTrue(email.html().contains(">" + link + "</p>"), "the visible fallback link is the same address");
        assertEquals(2, count(email.html(), link), "exactly the button and the fallback, nothing else");
        assertTrue(email.text().contains("Open Taskotel Login: " + link));
        assertEquals(1, count(email.text(), link));
        assertFalse((email.html() + email.text() + email.subject()).toLowerCase().contains("localhost"), "no localhost in a production email");
    }

    @Test
    @DisplayName("A local invitation email still links to the local login page (button and fallback alike)")
    public void localInvitationEmail() throws IOException {
        String loginUrl = AccountInvitationService.loginUrlFor(baseUrl(null, Map.of()));
        EmailContent email = NotificationEmailTemplates.accountInvitation("mia.rao", "Tmp@Pass1234xYz", loginUrl);
        assertTrue(email.html().contains("<a href=\"" + LOCAL + "/login\""));
        assertTrue(email.html().contains(">" + LOCAL + "/login</p>"));
        assertEquals(2, count(email.html(), LOCAL + "/login"));
        assertFalse(email.html().contains("taskotel.vercel.app"));
    }

    @Test
    @DisplayName("Through the real service and the SendGrid path: the production address reaches the sent message, in the button and the fallback")
    public void sentMessageCarriesTheProductionAddress() throws Exception {
        EmailService emailService = mock(EmailService.class);
        when(emailService.isConfigured()).thenReturn(true);
        AccountInvitationService service = new AccountInvitationService(emailService, baseUrl("application-prod.yml", Map.of()));
        assertEquals(PRODUCTION + "/login", service.loginUrl());
        service.send("new.user@hotel.test", "new.user", "Tmp@Pass1234xYz");
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAccountInvitationEmail(anyString(), anyString(), anyString(), url.capture());
        assertEquals(PRODUCTION + "/login", url.getValue());

        SendGrid client = mock(SendGrid.class);
        Response accepted = new Response();
        accepted.setStatusCode(202);
        when(client.api(any(Request.class))).thenReturn(accepted);
        new SendGridEmailService(new SendGridProperties("SG.fake-test-key", "noreply@hotelwb.com", "Taskotel"), client)
                .sendAccountInvitationEmail("new.user@hotel.test", "new.user", "Tmp@Pass1234xYz", url.getValue());
        ArgumentCaptor<Request> sent = ArgumentCaptor.forClass(Request.class);
        verify(client).api(sent.capture());
        String body = sent.getValue().getBody();
        assertTrue(count(body, PRODUCTION + "/login") >= 3, "text part once, HTML part twice (button and fallback)");
        assertFalse(body.toLowerCase().contains("localhost"));
    }

    @Test
    @DisplayName("The production address is written down in one configuration place only, and no Java code hard-codes a frontend address")
    public void productionAddressIsDefinedOnce() throws IOException {
        List<String> withProductionAddress;
        List<String> withLocalhostInJava;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            List<Path> all = files.filter(Files::isRegularFile).collect(Collectors.toList());
            withProductionAddress = all.stream().filter(p -> read(p).contains("taskotel.vercel.app")).map(p -> Path.of("src/main").relativize(p).toString().replace(java.io.File.separatorChar, '/')).sorted().collect(Collectors.toList());
            withLocalhostInJava = all.stream().filter(p -> p.toString().endsWith(".java") && read(p).contains("localhost")).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
        assertEquals(List.of("resources/application.yml"), withProductionAddress, "the production address appears in application.yml only: " + withProductionAddress);
        // the only localhost in the Java code is the documented development default of the invitation service (and comments)
        assertTrue(withLocalhostInJava.stream().allMatch(name -> name.equals("AccountInvitationService.java") || name.equals("CorsConfig.java") || name.equals("DemoDataSeeder.java")
                        // production refuses localhost; these two name it as the value to reject
                        || name.equals("FrontendBaseUrl.java") || name.equals("ProductionFrontendUrlValidator.java")),
                "unexpected localhost in: " + withLocalhostInJava);
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
