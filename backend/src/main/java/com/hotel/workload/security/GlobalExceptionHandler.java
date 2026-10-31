package com.hotel.workload.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPasswordException(InvalidPasswordException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(CredentialsSetupException.class)
    public ResponseEntity<Map<String, String>> handleCredentialsSetupException(CredentialsSetupException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(com.hotel.workload.verification.EmailSendingException.class)
    public ResponseEntity<Map<String, Object>> handleEmailSendingException(com.hotel.workload.verification.EmailSendingException ex) {
        // The cause stays in the server log (it holds a status code at most, never a key or a code); the client gets a fixed message
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class).warn("Email could not be sent: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "We could not send the email right now. Please try again in a few minutes."));
    }

    // A multipart upload over the configured size (spring.servlet.multipart) is refused before it reaches a controller
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("status", 413, "message", com.hotel.workload.profile.ProfileImageValidator.TOO_LARGE_MESSAGE));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid request payload or date/time format"));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(org.springframework.web.server.ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", ex.getStatusCode().value(),
                "error", ex.getStatusCode().toString(),
                "message", message
        ));
    }
}
