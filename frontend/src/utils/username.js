// Username helpers for the Add User / Add New Staff forms. The backend only requires a non-blank, unique username,
// so these rules are what the form generates and what it will check; they never rewrite what the user typed.

export const USERNAME_MIN_LENGTH = 3;
export const GENERATED_MAX_LENGTH = 30;
export const USERNAME_MAX_LENGTH = 50;

const nameWords = (fullName) =>
  String(fullName || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .split(/\s+/)
    .map((word) => word.replace(/[^a-z0-9]/g, ''))
    .filter(Boolean);

/** "Ayush Mallick" -> "ayushmallick". Lowercase letters and digits only; empty when the name is too short to use. */
export const generateUsername = (fullName) => {
  const username = nameWords(fullName).join('').slice(0, GENERATED_MAX_LENGTH);
  return username.length >= USERNAME_MIN_LENGTH ? username : '';
};

/** 'ok' when the username can be checked, otherwise why not: 'empty', 'space' or 'long'. */
export const validateUsername = (username) => {
  const value = String(username || '').trim();
  if (!value) return 'empty';
  if (/\s/.test(value)) return 'space';
  if (value.length > USERNAME_MAX_LENGTH) return 'long';
  return 'ok';
};

export const USERNAME_INVALID_MESSAGES = {
  space: 'Usernames cannot contain spaces.',
  long: `Usernames can be at most ${USERNAME_MAX_LENGTH} characters.`,
};

const withSuffix = (base, suffix) => base.slice(0, USERNAME_MAX_LENGTH - suffix.length) + suffix;

/** Alternatives to try when a username is taken, most natural first: first.last / first_last, then numbered. */
export const candidateUsernames = (base, fullName, limit = 6) => {
  const words = nameWords(fullName);
  const candidates = [];
  if (words.length >= 2 && words.join('') === base) {
    candidates.push(`${words[0]}.${words[words.length - 1]}`, `${words[0]}_${words[words.length - 1]}`);
  }
  candidates.push(...['1', '2', '3', '01', '123'].map((suffix) => withSuffix(base, suffix)));
  return [...new Set(candidates)]
    .filter((candidate) => candidate !== base && validateUsername(candidate) === 'ok')
    .slice(0, limit);
};
