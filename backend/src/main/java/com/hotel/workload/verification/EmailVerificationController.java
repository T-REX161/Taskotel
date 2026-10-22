package com.hotel.workload.verification;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth/email-verification")
public class EmailVerificationController {

    private final VerificationCodeService verificationCodeService;

    public EmailVerificationController(VerificationCodeService verificationCodeService) {
        this.verificationCodeService = verificationCodeService;
    }

    /**
     * Generates and dispatches a verification code to the user's email.
     * Accepts either an authenticated user's identity (via Principal) or a user identifier in the request body.
     */
    @PostMapping("/send")
    public ResponseEntity<VerificationResponse> sendVerificationCode(
            @RequestBody(required = false) SendVerificationRequest request,
            Principal principal) {
        String identifier = null;

        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            identifier = principal.getName();
        } else if (request != null) {
            identifier = request.getEffectiveIdentifier();
        }

        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new VerificationResponse(false, "User identifier is required"));
        }

        verificationCodeService.sendVerificationCode(identifier);
        return ResponseEntity.ok(new VerificationResponse(true, "Verification code sent successfully to email"));
    }

    /**
     * Verifies the 6-digit code submitted for the given user identifier.
     * Accepts either an authenticated user's identity (via Principal) or a user identifier in the request body.
     */
    @PostMapping("/verify")
    public ResponseEntity<VerificationResponse> verifyCode(
            @Valid @RequestBody VerifyCodeRequest request,
            Principal principal) {
        String identifier = null;

        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            identifier = principal.getName();
        } else if (request != null) {
            identifier = request.getEffectiveIdentifier();
        }

        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new VerificationResponse(false, "User identifier is required"));
        }

        String setupToken = verificationCodeService.verifyCode(identifier, request.getCode());
        return ResponseEntity.ok(new VerificationResponse(true, "Email verification successful", setupToken));
    }

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<VerificationResponse> handleVerificationException(VerificationException ex) {
        return ResponseEntity.badRequest()
                .body(new VerificationResponse(false, ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<VerificationResponse> handleUserNotFoundException(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new VerificationResponse(false, ex.getMessage()));
    }

    @ExceptionHandler(EmailSendingException.class)
    public ResponseEntity<VerificationResponse> handleEmailSendingException(EmailSendingException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new VerificationResponse(false, "Failed to send verification email"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<VerificationResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(new VerificationResponse(false, message));
    }
}
