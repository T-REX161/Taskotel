package com.hotel.workload.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CloudinaryPropertiesTest {

    @Test
    @DisplayName("Missing values and the .env.example placeholders count as not configured; only the variable names are reported")
    void missingAndPlaceholderValues() {
        CloudinaryProperties empty = new CloudinaryProperties("", null, "  ", null);
        assertFalse(empty.isConfigured());
        assertEquals(List.of("CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET"), empty.missingSettings());

        CloudinaryProperties placeholders = new CloudinaryProperties("your_cloud_name", "your_api_key", "your_api_secret", null);
        assertFalse(placeholders.isConfigured());
        assertEquals(3, placeholders.missingSettings().size());

        CloudinaryProperties onlySecretMissing = new CloudinaryProperties("demo-cloud", "123456789012345", "your_api_secret", null);
        assertEquals(List.of("CLOUDINARY_API_SECRET"), onlySecretMissing.missingSettings());
    }

    @Test
    @DisplayName("Real values are configured; surrounding spaces and one pair of quotes from a .env line are dropped")
    void realValuesAreCleaned() {
        CloudinaryProperties properties = new CloudinaryProperties(" demo-cloud ", "\"123456789012345\"", "'s3cr3t-Value_x' ", null);
        assertTrue(properties.isConfigured());
        assertEquals("demo-cloud", properties.getCloudName());
        assertEquals("123456789012345", properties.getApiKey());
        assertEquals("s3cr3t-Value_x", properties.getApiSecret());
    }

    @Test
    @DisplayName("Values that cannot be right are reported by name and reason, never by value, and leave Cloudinary switched off")
    void invalidValues() {
        CloudinaryProperties keyLabelAsCloudName = new CloudinaryProperties("Taskotel Backend", "123456789012345", "s3cr3t-Value_x", null);
        assertFalse(keyLabelAsCloudName.isConfigured());
        assertTrue(keyLabelAsCloudName.missingSettings().isEmpty(), "nothing is missing");
        assertEquals(1, keyLabelAsCloudName.invalidSettings().size());
        String problem = keyLabelAsCloudName.invalidSettings().get(0);
        assertTrue(problem.startsWith("CLOUDINARY_CLOUD_NAME is not a cloud name"), problem);
        assertFalse(problem.contains("Taskotel Backend") || problem.contains("123456789012345") || problem.contains("s3cr3t-Value_x"), problem);

        for (String bad : new String[] {"my cloud", "demo/x", "demo?x", "https://res.cloudinary.com/demo", "demo@x"}) {
            assertFalse(new CloudinaryProperties(bad, "123456789012345", "s3cr3t-Value_x", null).isConfigured(), bad);
        }
        assertEquals(List.of("CLOUDINARY_API_KEY must not contain spaces", "CLOUDINARY_API_SECRET must not contain spaces"),
                new CloudinaryProperties("demo", "1234 5678", "abc def", null).invalidSettings());
        for (String good : new String[] {"demo", "dxyz123abc", "my-hotel_cloud", "Demo2"}) {
            CloudinaryProperties properties = new CloudinaryProperties(good, "123456789012345", "s3cr3t-Value_x", null);
            assertTrue(properties.isConfigured(), good);
            assertTrue(properties.invalidSettings().isEmpty(), good);
        }
    }

    @Test
    @DisplayName("The folder defaults to taskotel/profile-pictures")
    void defaultFolder() {
        assertEquals("taskotel/profile-pictures", new CloudinaryProperties().getFolder());
        assertEquals("taskotel/profile-pictures", new CloudinaryProperties("a", "b", "c", " ").getFolder());
        assertEquals("taskotel/profile-pictures", CloudinaryProperties.DEFAULT_FOLDER);
    }

    @Test
    @DisplayName("toString never contains the key or the secret")
    void toStringHidesCredentials() {
        String text = new CloudinaryProperties("demo-cloud", "123456789012345", "s3cr3t-Value_x", null).toString();
        assertFalse(text.contains("123456789012345"));
        assertFalse(text.contains("s3cr3t-Value_x"));
        assertTrue(text.contains("configured=true"));
    }
}
