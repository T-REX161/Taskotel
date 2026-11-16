package com.hotel.workload.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sendgrid")
public class SendGridProperties {

    private String apiKey = "";
    private String fromEmail = "";
    private String fromName = "Taskotel";
    /**
     * HTTP timeouts for every call to SendGrid, in milliseconds. Without them a stalled connection would block the calling thread (and any
     * database connection it holds) indefinitely. Bound as sendgrid.connect-timeout-ms and so on.
     */
    private int connectTimeoutMs = 5000;
    private int connectionRequestTimeoutMs = 5000;
    private int socketTimeoutMs = 10000;

    public SendGridProperties() {}

    public SendGridProperties(String apiKey, String fromEmail, String fromName) {
        this.apiKey = apiKey != null ? apiKey : "";
        this.fromEmail = fromEmail != null ? fromEmail : "";
        this.fromName = fromName != null ? fromName : "Taskotel";
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getFromEmail() {
        return fromEmail;
    }

    public void setFromEmail(String fromEmail) {
        this.fromEmail = fromEmail;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getConnectionRequestTimeoutMs() {
        return connectionRequestTimeoutMs;
    }

    public void setConnectionRequestTimeoutMs(int connectionRequestTimeoutMs) {
        this.connectionRequestTimeoutMs = connectionRequestTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }
}
