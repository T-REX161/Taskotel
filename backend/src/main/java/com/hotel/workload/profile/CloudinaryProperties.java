package com.hotel.workload.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cloudinary settings for profile pictures. The credentials come from the environment variables CLOUDINARY_CLOUD_NAME,
 * CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET, or from the git-ignored backend/.env file; never from a tracked file.
 */
@Component
@ConfigurationProperties(prefix = "cloudinary")
public class CloudinaryProperties {

    public static final String DEFAULT_FOLDER = "taskotel/profile-pictures";

    private String cloudName = "";
    private String apiKey = "";
    private String apiSecret = "";
    private String folder = DEFAULT_FOLDER;

    public CloudinaryProperties() {}

    public CloudinaryProperties(String cloudName, String apiKey, String apiSecret, String folder) {
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.folder = folder;
    }

    /**
     * The names of the settings that are missing or still hold a placeholder copied from .env.example ("your_..."). Only
     * names are ever reported, never values.
     */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        if (!isSet(cloudName)) missing.add("CLOUDINARY_CLOUD_NAME");
        if (!isSet(apiKey)) missing.add("CLOUDINARY_API_KEY");
        if (!isSet(apiSecret)) missing.add("CLOUDINARY_API_SECRET");
        return missing;
    }

    /**
     * Settings that are filled in but cannot be right, each with the reason (never the value). The cloud name is part of every
     * request URL, so a space in it (for example the API key's label "Taskotel Backend") makes every request fail before it is sent.
     */
    public List<String> invalidSettings() {
        List<String> invalid = new ArrayList<>();
        if (isSet(cloudName) && !getCloudName().matches("[A-Za-z0-9_-]+")) {
            invalid.add("CLOUDINARY_CLOUD_NAME is not a cloud name: use the Cloud name shown in the Cloudinary console "
                    + "(letters, digits, - or _ only, no spaces), not the API key's name");
        }
        if (isSet(apiKey) && !getApiKey().matches("\\S+")) {
            invalid.add("CLOUDINARY_API_KEY must not contain spaces");
        }
        if (isSet(apiSecret) && !getApiSecret().matches("\\S+")) {
            invalid.add("CLOUDINARY_API_SECRET must not contain spaces");
        }
        return invalid;
    }

    public boolean isConfigured() {
        return missingSettings().isEmpty() && invalidSettings().isEmpty();
    }

    static boolean isSet(String value) {
        String cleaned = clean(value);
        return !cleaned.isEmpty() && !cleaned.toLowerCase(Locale.ROOT).startsWith("your_");
    }

    /** Trims, and drops one pair of surrounding quotes: .env is read as a properties file, which would keep them. */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    public String getCloudName() { return clean(cloudName); }
    public void setCloudName(String cloudName) { this.cloudName = cloudName; }
    public String getApiKey() { return clean(apiKey); }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return clean(apiSecret); }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    public String getFolder() {
        String cleaned = clean(folder);
        return cleaned.isEmpty() ? DEFAULT_FOLDER : cleaned;
    }

    public void setFolder(String folder) { this.folder = folder; }

    /** Never prints the key or the secret, whatever logs or debuggers call this. */
    @Override
    public String toString() {
        return "CloudinaryProperties{folder=" + getFolder() + ", configured=" + isConfigured() + "}";
    }
}
