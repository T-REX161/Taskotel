package com.hotel.workload.security;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class PasswordValidatorService {

    // 8+ char, uppercase, lowercase, number, special char
    private static final String PASSWORD_PATTERN = 
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!_~.*\\-])(?=\\S+$).{8,}$";

    private static final Pattern pattern = Pattern.compile(PASSWORD_PATTERN);

    public boolean isValid(String password) {
        if (password == null) {
            return false;
        }
        return pattern.matcher(password).matches();
    }
    
    public void validate(String password) {
        if (!isValid(password)) {
            throw new InvalidPasswordException("Password must be at least 8 characters long, contain at least one uppercase letter, one lowercase letter, one digit, and one special character.");
        }
    }
}
