package com.hotel.workload.profile;

import com.cloudinary.Cloudinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Cloudinary client from {@link CloudinaryProperties}. When a credential is missing the application still starts:
 * profile pictures are simply unavailable, and the log names the missing variables (never their values).
 */
@Configuration
public class CloudinaryConfig {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryConfig.class);

    /** Seconds to wait for Cloudinary to connect and answer, so a slow image service cannot hold request threads forever. */
    static final int TIMEOUT_SECONDS = 30;

    @Bean
    public ProfileImageStorage profileImageStorage(CloudinaryProperties properties) {
        List<String> missing = properties.missingSettings();
        List<String> invalid = properties.invalidSettings();
        if (!missing.isEmpty() || !invalid.isEmpty()) {
            if (!missing.isEmpty()) {
                log.warn("Profile picture uploads are off until these are set in the environment or backend/.env: {}", String.join(", ", missing));
            }
            for (String problem : invalid) {
                log.warn("Profile picture uploads are off: {} (environment or backend/.env)", problem);
            }
            return new CloudinaryImageStorage(null, properties.getFolder());
        }
        log.info("Profile pictures are stored in Cloudinary, folder {}", properties.getFolder());
        return new CloudinaryImageStorage(createClient(properties), properties.getFolder());
    }

    static Cloudinary createClient(CloudinaryProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put("cloud_name", properties.getCloudName());
        config.put("api_key", properties.getApiKey());
        config.put("api_secret", properties.getApiSecret());
        config.put("secure", true);
        config.put("timeout", TIMEOUT_SECONDS);
        config.put("analytics", false); // plain delivery URLs, without the SDK usage marker
        return new Cloudinary(config);
    }
}
