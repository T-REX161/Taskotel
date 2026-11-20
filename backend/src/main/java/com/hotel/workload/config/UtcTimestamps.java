package com.hotel.workload.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Every moment the API sends carries its zone, so a browser can show it in the viewer's own time zone.
 *
 * <p>The entities keep their moments as {@link LocalDateTime} (a date and time with no zone) written with {@code LocalDateTime.now()},
 * which is a clock reading in the JVM's default zone. The production container's JVM runs in UTC, so production stores UTC readings; a
 * laptop in India stores India readings. The API used to send those readings with no zone, and the browser (and the Account Activity card)
 * showed the reading as it was: the UTC server's 05:25 instead of 10:55 in India, while a server in India happened to match.
 *
 * <p>Now each {@code LocalDateTime} is sent as an instant, the reading interpreted in the zone the JVM wrote it in
 * ({@code 2026-11-20T05:25:00Z} from the UTC server), whatever machine runs it. Stored data is not touched, and nothing depends on the
 * machine's zone being UTC, although production pins it ({@code -Duser.timezone=UTC} in the Dockerfile) so that stored readings are UTC
 * on every deployment. Plain dates ({@code LocalDate}, such as a task's due date) and times of day ({@code LocalTime}, such as a deadline)
 * are not moments and are sent exactly as before.
 */
@Configuration
public class UtcTimestamps {

    private static final Logger log = LoggerFactory.getLogger(UtcTimestamps.class);

    /** A stored reading as an instant string with the zone marker, e.g. {@code 2026-11-20T05:25:00Z}. */
    public static String asInstant(LocalDateTime storedReading) {
        return asInstant(storedReading, ZoneId.systemDefault());
    }

    /** The same, naming the zone the reading was written in. */
    public static String asInstant(LocalDateTime storedReading, ZoneId writtenIn) {
        return storedReading.atZone(writtenIn).toInstant().toString();
    }

    @PostConstruct
    void reportZone() {
        ZoneId zone = ZoneId.systemDefault();
        if ("UTC".equals(zone.getId()) || "Z".equals(zone.getId()) || "Etc/UTC".equals(zone.getId())) {
            log.info("Timestamps are stored in UTC (JVM time zone {}).", zone);
        } else {
            log.warn("The JVM time zone is {}, not UTC: new timestamps are stored as {} clock readings. The API still sends each one as the correct instant. "
                    + "Production should run with -Duser.timezone=UTC so stored readings are UTC everywhere.", zone, zone);
        }
    }

    @Bean
    public JacksonModule utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("local-date-time-as-instant");
        module.addSerializer(LocalDateTime.class, new ValueSerializer<LocalDateTime>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializationContext context) {
                generator.writeString(asInstant(value));
            }
        });
        return module;
    }
}
