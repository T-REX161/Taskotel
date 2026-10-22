package com.hotel.workload.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordValidatorServiceTest {

    private PasswordValidatorService validator;

    @BeforeEach
    public void setUp() {
        validator = new PasswordValidatorService();
    }

    @Test
    @DisplayName("Reject password shorter than 8 characters")
    public void testPasswordTooShort() {
        assertFalse(validator.isValid("Pass@1"));
        assertFalse(validator.isValid("Pass@12"));

        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate("Pass@1"));
        assertTrue(ex.getMessage().contains("at least 8 characters"));
    }

    @Test
    @DisplayName("Reject password missing uppercase letter")
    public void testPasswordMissingUppercase() {
        assertFalse(validator.isValid("pass@123"));
        assertFalse(validator.isValid("lowercase1!"));

        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate("pass@123"));
        assertTrue(ex.getMessage().contains("uppercase letter"));
    }

    @Test
    @DisplayName("Reject password missing lowercase letter")
    public void testPasswordMissingLowercase() {
        assertFalse(validator.isValid("PASS@123"));
        assertFalse(validator.isValid("UPPERCASE1!"));

        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate("PASS@123"));
        assertTrue(ex.getMessage().contains("lowercase letter"));
    }

    @Test
    @DisplayName("Reject password missing number")
    public void testPasswordMissingNumber() {
        assertFalse(validator.isValid("Password@"));
        assertFalse(validator.isValid("NoDigitsHere!"));

        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate("Password@"));
        assertTrue(ex.getMessage().contains("digit"));
    }

    @Test
    @DisplayName("Reject password missing special character")
    public void testPasswordMissingSpecialChar() {
        assertFalse(validator.isValid("Password123"));
        assertFalse(validator.isValid("NoSpecialChar9"));

        InvalidPasswordException ex = assertThrows(InvalidPasswordException.class, () -> validator.validate("Password123"));
        assertTrue(ex.getMessage().contains("special character"));
    }

    @Test
    @DisplayName("Reject password containing whitespace")
    public void testPasswordWithWhitespace() {
        assertFalse(validator.isValid("Pass word@1"));
        assertFalse(validator.isValid(" Pass@123"));
        assertFalse(validator.isValid("Pass@123 "));
        assertFalse(validator.isValid("Pass\t@123"));

        assertThrows(InvalidPasswordException.class, () -> validator.validate("Pass word@1"));
    }

    @Test
    @DisplayName("Reject null or blank password")
    public void testNullOrBlankPassword() {
        assertFalse(validator.isValid(null));
        assertFalse(validator.isValid(""));
        assertFalse(validator.isValid("   "));

        assertThrows(InvalidPasswordException.class, () -> validator.validate(null));
        assertThrows(InvalidPasswordException.class, () -> validator.validate(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Password@1",
            "Password#1",
            "Password$1",
            "Password%1",
            "Password^1",
            "Password&1",
            "Password+1",
            "Password=1",
            "Password!1",
            "Password_1",
            "Password~1",
            "Password.1",
            "Password*1",
            "Password-1",
            "ComplexPass#2026",
            "HotelStaff@99"
    })
    @DisplayName("Accept valid passwords meeting all criteria with various special characters")
    public void testValidPasswords(String validPassword) {
        assertTrue(validator.isValid(validPassword), "Password should be valid: " + validPassword);
        assertDoesNotThrow(() -> validator.validate(validPassword));
    }
}
