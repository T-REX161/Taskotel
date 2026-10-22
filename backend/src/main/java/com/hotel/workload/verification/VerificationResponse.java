package com.hotel.workload.verification;

public class VerificationResponse {

    private boolean success;
    private String message;
    private String setupToken;

    public VerificationResponse() {}

    public VerificationResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
    
    public VerificationResponse(boolean success, String message, String setupToken) {
        this.success = success;
        this.message = message;
        this.setupToken = setupToken;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSetupToken() {
        return setupToken;
    }

    public void setSetupToken(String setupToken) {
        this.setupToken = setupToken;
    }
}
