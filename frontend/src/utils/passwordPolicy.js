/**
 * Shared frontend password validation policy.
 * Mirrors the server-side rules in PasswordValidatorService:
 * 1. At least 8 characters
 * 2. At least one uppercase letter
 * 3. At least one lowercase letter
 * 4. At least one number
 * 5. At least one special character (@#$%^&+=!_~.*-)
 */
export const evaluatePasswordRules = (password = '') => {
  const pwd = password || '';
  const ruleMinLength = pwd.length >= 8;
  const ruleUppercase = /[A-Z]/.test(pwd);
  const ruleLowercase = /[a-z]/.test(pwd);
  const ruleNumber = /[0-9]/.test(pwd);
  const ruleSpecialChar = /[@#$%^&+=!_~.*-]/.test(pwd);
  const hasNoWhitespace = !/\s/.test(pwd);

  const isValid =
    ruleMinLength &&
    ruleUppercase &&
    ruleLowercase &&
    ruleNumber &&
    ruleSpecialChar &&
    hasNoWhitespace;

  return {
    ruleMinLength,
    ruleUppercase,
    ruleLowercase,
    ruleNumber,
    ruleSpecialChar,
    hasNoWhitespace,
    isValid,
  };
};
