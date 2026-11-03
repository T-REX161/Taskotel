package com.hotel.workload.profile;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/** Rules for the phone number: a plain, reasonable number with an optional country code. Nothing here verifies that it is real. */
public final class PhoneNumbers {

    public static final int MAX_LENGTH = 50;
    static final int MIN_DIGITS = 7;
    static final int MAX_DIGITS = 15; // the international (E.164) maximum
    static final String EMPTY_MESSAGE = "Phone number cannot be empty.";
    static final String INVALID_MESSAGE = "Enter a valid phone number, for example +91 98765 43210.";

    /** Optional leading +, then digits with single spaces, hyphens, dots or parentheses between them. */
    private static final Pattern SHAPE = Pattern.compile("^\\+?[0-9(][0-9 ().-]*$");

    private PhoneNumbers() {}

    /** The trimmed number (inner whitespace collapsed to single spaces), or a 400 explaining what is wrong. */
    public static String validate(String requested) {
        String number = requested == null ? "" : requested.strip().replaceAll("\\s+", " ");
        if (number.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, EMPTY_MESSAGE);
        }
        if (number.length() > MAX_LENGTH || !SHAPE.matcher(number).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_MESSAGE);
        }
        long digits = number.chars().filter(Character::isDigit).count();
        if (digits < MIN_DIGITS || digits > MAX_DIGITS || !balancedBrackets(number)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_MESSAGE);
        }
        return number;
    }

    private static boolean balancedBrackets(String number) {
        int open = 0;
        for (char c : number.toCharArray()) {
            if (c == '(') open++;
            if (c == ')' && --open < 0) return false;
        }
        return open == 0;
    }
}
