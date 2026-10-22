package com.hotel.workload.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SendGridPropertiesTest {

    @Autowired
    private SendGridProperties sendGridProperties;

    @Test
    @DisplayName("SendGrid configuration properties are successfully read from configuration/environment")
    public void testSendGridPropertiesLoadedFromConfiguration() {
        assertNotNull(sendGridProperties, "SendGridProperties bean must be loaded into ApplicationContext");
        assertNotNull(sendGridProperties.getApiKey(), "API key must not be null");
        assertNotNull(sendGridProperties.getFromEmail(), "From email must not be null");
        assertNotNull(sendGridProperties.getFromName(), "From name must not be null");

        // Checks default values set in test application.yml or fallbacks
        assertFalse(sendGridProperties.getFromEmail().isBlank(), "From email must have a configured value");
        assertFalse(sendGridProperties.getFromName().isBlank(), "From name must have a configured value");
    }

    @Test
    @DisplayName("SendGridProperties custom instantiation reads manual parameters")
    public void testSendGridPropertiesCustomInstantiation() {
        SendGridProperties props = new SendGridProperties("custom-key", "custom@hotel.com", "Custom Hotel");
        assertEquals("custom-key", props.getApiKey());
        assertEquals("custom@hotel.com", props.getFromEmail());
        assertEquals("Custom Hotel", props.getFromName());
    }
}
