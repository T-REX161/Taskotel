import { evaluatePasswordRules } from './passwordPolicy.js';

const SPECIALS = '@#$%&!*';
const DIGITS = '0123456789';

export const PASSWORD_LENGTH = 8;

/**
 * Uniform integer in [0, max) from the Web Crypto API. Values that would make the modulo uneven are discarded and redrawn
 * (rejection sampling). There is deliberately no weaker fallback: without Web Crypto it fails instead of weakening the password.
 */
export const randomInt = (max, cryptoApi = globalThis.crypto) => {
  if (!cryptoApi || typeof cryptoApi.getRandomValues !== 'function') throw new Error('Secure random numbers are not available in this browser.');
  const limit = Math.floor(0x100000000 / max) * max;
  const buffer = new Uint32Array(1);
  do cryptoApi.getRandomValues(buffer);
  while (buffer[0] >= limit);
  return buffer[0] % max;
};
const pick = (chars) => chars[randomInt(chars.length)];

const FALLBACK_NAME = 'Staff';
// 8 characters = name + 1 special + at least 1 digit, and the policy needs an uppercase and a lowercase letter (2 letters at least)
const MIN_NAME_LETTERS = 2;
const MAX_NAME_LETTERS = PASSWORD_LENGTH - 2;

/**
 * The name part: the letters of the FIRST word of the full name only (accents and punctuation dropped), initial capitalised and the
 * rest kept as typed. A first name longer than 6 letters is cut to its first 6 so that a special character and at least one digit still
 * fit in 8 characters. A name with no usable letters, or a single letter (which cannot hold both an uppercase and a lowercase
 * letter), uses the fallback "Staff". No letter that is not in the first name is ever added.
 */
export const passwordBase = (fullName = '') => {
  const first = String(fullName || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .split(/\s+/)
    .map((word) => word.replace(/[^A-Za-z]/g, ''))
    .find(Boolean) || '';
  if (first.length < MIN_NAME_LETTERS) return FALLBACK_NAME;
  const letters = first.slice(0, MAX_NAME_LETTERS);
  const base = letters.charAt(0).toUpperCase() + letters.slice(1);
  return /[a-z]/.test(base) ? base : base.charAt(0) + base.slice(1).toLowerCase(); // "RAI" -> "Rai"
};

/**
 * Password = first name + one special character + digits, 8 characters in all, e.g. "Dua#4827": the digits fill
 * every position the name and the special character do not (7 - name length of them). The special character is inserted at a random
 * point of name+digits that is not inside the name, so it can come first, directly after the name, in the middle of the digits or
 * last. Every random choice comes from the Web Crypto API, and the result always satisfies every password rule.
 */
export const generateTemporaryPassword = (fullName = '') => {
  const name = passwordBase(fullName);
  const digits = Array.from({ length: PASSWORD_LENGTH - 1 - name.length }, () => pick(DIGITS)).join('');
  const body = name + digits;
  // insertion points 0 and name.length..body.length (never between the letters of the name)
  const slot = randomInt(body.length - name.length + 2);
  const at = slot === 0 ? 0 : name.length + slot - 1;
  const password = body.slice(0, at) + pick(SPECIALS) + body.slice(at);
  if (password.length !== PASSWORD_LENGTH || !evaluatePasswordRules(password).isValid) throw new Error('Generated password is invalid.');
  return password;
};
/** Copies text with the async Clipboard API, falling back to a hidden textarea. Resolves true on success, false otherwise. */
export const copyText = async (text, env = globalThis) => {
  try {
    if (env.navigator?.clipboard?.writeText) {
      await env.navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to the legacy path */
  }
  try {
    const doc = env.document;
    if (!doc?.body || typeof doc.execCommand !== 'function') return false;
    const area = doc.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    doc.body.appendChild(area);
    area.select();
    const copied = doc.execCommand('copy');
    doc.body.removeChild(area);
    return Boolean(copied);
  } catch {
    return false;
  }
};
