package com.hotel.workload.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The SendGrid timeouts are ordinary configuration properties (sendgrid.*-timeout-ms), bound without any code change. */
@SpringBootTest
@TestPropertySource(properties = {
        "sendgrid.connect-timeout-ms=1111",
        "sendgrid.connection-request-timeout-ms=2222",
        "sendgrid.socket-timeout-ms=3333"
})
public class SendGridTimeoutBindingTest {

    @Autowired private SendGridProperties properties;

    @Test
    @DisplayName("The three timeouts are bound from configuration")
    public void timeoutsAreBound() {
        assertEquals(1111, properties.getConnectTimeoutMs());
        assertEquals(2222, properties.getConnectionRequestTimeoutMs());
        assertEquals(3333, properties.getSocketTimeoutMs());
    }
}
