package com.hotel.workload.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sendgrid")
public class SendGridProperties {

    private String apiKey = "";
    private String fromEmail = "noreply@hotelwb.com";
    private String fromName = "EquiFlow";

    public SendGridProperties() {}

    public SendGridProperties(String apiKey, String fromEmail, String fromName) {
        this.apiKey = apiKey != null ? apiKey : "";
        this.fromEmail = fromEmail != null ? fromEmail : "noreply@hotelwb.com";
        this.fromName = fromName != null ? fromName : "EquiFlow";
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

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }
}
