package com.hotel.workload.verification;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Public onboarding endpoints. They must not reveal whether an account exists, so every accepted send gets the same answer and every
 * failed verification the same one (see {@link VerificationCodeServiceImpl}). Known limitation of this stage: dispatch is synchronous, so a
 * send to a real account still takes longer than one to an unknown account.
 */
@RestController
@RequestMapping("/api/auth/email-verification")
public class EmailVerificationController {

    /** The one reply to every send request that was accepted. */
    public static final String SEND_MESSAGE = "If this account can be verified, a verification code has been sent to its email address.";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailVerificationController.class);

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

        try {
            verificationCodeService.sendVerificationCode(identifier);
        } catch (EmailSendingException e) {
            // Chosen behaviour (Step 2J): a failed delivery is NOT reported to the public caller. Reporting it for a real account would tell an
            // outsider which accounts exist (an unknown account never reaches the email service). The failure is logged here without the
            // identifier; no new code was stored, so any earlier valid code is untouched, and the person simply asks again (signing in
            // sends a fresh code). Login handling of the same failure is a later stage.
            log.warn("A verification email could not be delivered: {}", e.getMessage());
        }
        // The same answer whether or not an account matches, is active, or was sent anything (Step 2J): the reply must not tell
        // an outsider which usernames and emails exist
        return ResponseEntity.ok(new VerificationResponse(true, SEND_MESSAGE));
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
