package com.hotel.workload.security;

import com.hotel.workload.verification.VerificationCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

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
        Optional<User> optionalUser = userRepository.findByUsername(loginRequest.getUsername());

        if (optionalUser.isEmpty()) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        User user = optionalUser.get();

        if (!user.isActive()) {
            throw new InvalidCredentialsException("User account is disabled");
        }

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid username or password");
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
        
        userRepository.save(user);

        // Generate standard JWT now that setup is complete
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return LoginResponse.success(token, user.getUsername(), user.getRole());
    }
}

