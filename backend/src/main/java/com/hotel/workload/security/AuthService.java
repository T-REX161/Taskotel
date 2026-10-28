package com.hotel.workload.security;

import com.hotel.workload.verification.VerificationCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    // The same message whether the username/email or the password was wrong
    private static final String INVALID_LOGIN = "Invalid username/email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final VerificationCodeService verificationCodeService;
    private final PasswordValidatorService passwordValidatorService;

    @Autowired
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       VerificationCodeService verificationCodeService,
                       PasswordValidatorService passwordValidatorService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.verificationCodeService = verificationCodeService;
        this.passwordValidatorService = passwordValidatorService != null ? passwordValidatorService : new PasswordValidatorService();
    }

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this(userRepository, passwordEncoder, jwtUtil, null, new PasswordValidatorService());
    }

    public LoginResponse authenticate(LoginRequest loginRequest) {
        Optional<User> optionalUser = findByUsernameOrEmail(loginRequest.getUsername());

        if (optionalUser.isEmpty()) {
            throw new InvalidCredentialsException(INVALID_LOGIN);
        }

        User user = optionalUser.get();

        if (!user.isActive()) {
            throw new InvalidCredentialsException("User account is disabled");
        }

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException(INVALID_LOGIN);
        }

        if (user.getRole() == Role.STAFF && !user.isEmailVerified()) {
            if (verificationCodeService != null) {
                verificationCodeService.sendVerificationCode(user.getUsername());
            }
            return LoginResponse.verificationRequired(user.getUsername(), user.getEmail());
        }

        if (user.isCredentialsSetupRequired()) {
            throw new InvalidCredentialsException("Account setup is required. Please follow the link sent to your email or use your setup token.");
        }

        if (user.isFirstLogin()) {
            user.setFirstLogin(false);
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole());
    }

    /**
     * The sign-in identifier is a username or a registered email address. A username match wins, so existing
     * username logins behave exactly as before; otherwise the identifier is matched against emails, ignoring case
     * and surrounding spaces. If more than one account has that email (differing only in case), nobody is signed
     * in by email: the login fails like any other and a warning is logged without the address.
     */
    private Optional<User> findByUsernameOrEmail(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        Optional<User> byUsername = userRepository.findByUsername(identifier);
        if (byUsername.isPresent()) {
            return byUsername;
        }
        String email = identifier.trim();
        if (!email.contains("@")) {
            return Optional.empty();
        }
        List<User> byEmail = userRepository.findAllByEmailIgnoreCase(email);
        if (byEmail.size() > 1) {
            logger.warn("Email sign-in refused: {} accounts share the same email address, differing only in case", byEmail.size());
            return Optional.empty();
        }
        return byEmail.stream().findFirst();
    }

    public LoginResponse setupCredentials(SetupCredentialsRequest request) {
        if (request.getSetupToken() == null || request.getSetupToken().isBlank()) {
            throw new CredentialsSetupException("Setup token is required");
        }

        User user = userRepository.findBySetupToken(request.getSetupToken())
                .orElseThrow(() -> new CredentialsSetupException("Invalid or expired setup token"));

        if (!user.isCredentialsSetupRequired()) {
            throw new CredentialsSetupException("Credentials setup is not required for this user");
        }

        // Validate new password
        passwordValidatorService.validate(request.getNewPassword());

        // Ensure new username doesn't exist if changed
        if (!user.getUsername().equals(request.getNewUsername())) {
            if (userRepository.existsByUsername(request.getNewUsername())) {
                throw new CredentialsSetupException("Username already exists");
            }
        }

        user.setUsername(request.getNewUsername());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setCredentialsSetupRequired(false);
        user.setSetupToken(null);
        user.setFirstLogin(false);
        
        try {
            userRepository.save(user);
            userRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new CredentialsSetupException("Username already exists");
        }

        // Generate standard JWT now that setup is complete
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return LoginResponse.success(token, user.getUsername(), user.getRole());
    }
}

