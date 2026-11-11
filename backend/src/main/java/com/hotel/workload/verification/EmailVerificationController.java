package com.hotel.workload.verification;

import com.hotel.workload.security.AttemptLimiter;
import com.hotel.workload.security.VerificationAttemptLimiter;
import com.hotel.workload.security.VerificationSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /** The one reply to every request refused by a rate limit, whatever the identifier. */
    public static final String TOO_MANY_REQUESTS_MESSAGE = "Too many requests. Please wait a few minutes and try again.";

    private final VerificationCodeService verificationCodeService;
    private final VerificationSendLimiter sendLimiter;
    private final VerificationAttemptLimiter attemptLimiter;
    private final com.hotel.workload.security.ClientAddressResolver clientAddress;

    public EmailVerificationController(VerificationCodeService verificationCodeService, VerificationSendLimiter sendLimiter,
                                       VerificationAttemptLimiter attemptLimiter, com.hotel.workload.security.ClientAddressResolver clientAddress) {
        this.clientAddress = clientAddress;
        this.verificationCodeService = verificationCodeService;
        this.sendLimiter = sendLimiter;
        this.attemptLimiter = attemptLimiter;
    }

    /**
     * The client comes from the shared {@link com.hotel.workload.security.ClientAddressResolver}: the connection's own address, and forwarded
     * headers only from a proxy that has been explicitly configured as trusted (none by default), because any caller can set them.
     */
    private String client(HttpServletRequest http) {
        return clientAddress.resolve(http);
    }

    /** A 429 with one fixed message and Retry-After; it says nothing about the account. */
    private static ResponseEntity<VerificationResponse> tooManyRequests(AttemptLimiter.Decision decision) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
                .body(new VerificationResponse(false, TOO_MANY_REQUESTS_MESSAGE));
    }

    /**
     * Generates and dispatches a verification code to the user's email.
     * Accepts either an authenticated user's identity (via Principal) or a user identifier in the request body.
     */
    @PostMapping("/send")
    public ResponseEntity<VerificationResponse> sendVerificationCode(
            @RequestBody(required = false) SendVerificationRequest request,
            Principal principal, HttpServletRequest http) {
        String identifier = null;

        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            identifier = principal.getName();
        } else if (request != null) {
            identifier = request.getEffectiveIdentifier();
        }

        // Every request that reaches this method is charged first, and before the account is looked up, so a refusal is the same whether
        // or not the account exists and a request with no identifier cannot be sent for free
        AttemptLimiter.Decision decision = sendLimiter.begin(client(http), identifier == null ? "" : identifier);
        if (!decision.allowed()) {
            return tooManyRequests(decision);
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
            @RequestBody(required = false) VerifyCodeRequest request,
            Principal principal, HttpServletRequest http) {
        String identifier = null;

        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            identifier = principal.getName();
        } else if (request != null) {
            identifier = request.getEffectiveIdentifier();
        }

        // Charged first, exactly once, for EVERY request that reaches this method: a missing, blank or malformed code is not rejected
        // earlier by validation (see VerifyCodeRequest), so none of them is free. The malformed code itself is judged by the service,
        // which answers it with the same generic failure as a wrong one and never accepts it.
        String client = client(http);
        String limiterKey = identifier == null ? "" : identifier;
        AttemptLimiter.Decision decision = attemptLimiter.begin(client, limiterKey);
        if (!decision.allowed()) {
            return tooManyRequests(decision);
        }

        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new VerificationResponse(false, "User identifier is required"));
        }

        String setupToken = verificationCodeService.verifyCode(identifier, request == null ? null : request.getCode());
        attemptLimiter.succeeded(client, limiterKey); // a successful verification does not count against anyone
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

    /**
     * A body that is not readable (broken JSON, wrong types) is refused before the methods above run, so they cannot charge it. It is charged
     * here, against the client and an empty account key, on the limiter of the endpoint that was called; a client cannot send unlimited
     * garbage. The answer says nothing about any account: the generic failure for verify, a plain request error for send.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<VerificationResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest http) {
        boolean verifying = http.getRequestURI() != null && http.getRequestURI().endsWith("/verify");
        AttemptLimiter limiter = verifying ? attemptLimiter : sendLimiter;
        AttemptLimiter.Decision decision = limiter.begin(client(http), "");
        if (!decision.allowed()) {
            return tooManyRequests(decision);
        }
        return ResponseEntity.badRequest()
                .body(new VerificationResponse(false, verifying ? VerificationCodeServiceImpl.GENERIC_FAILURE : "Invalid request."));
    }
}
