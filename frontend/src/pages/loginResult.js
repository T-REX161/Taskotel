/**
 * Login outcome classification for LoginPage. Pure, so it can be tested without a DOM.
 *
 * Only a backend 401 counts as invalid credentials; server errors and network failures get their
 * own messages so they are never reported as a wrong username or password. A 429 from the login rate limiter is its own
 * outcome and shows the backend's message (when it is a usable string), never the generic server error. The invalid-credentials
 * message never says which of the two was wrong.
 */
export const LOGIN_MESSAGES = {
  invalid: 'Invalid username/email or password. Please try again.',
  rateLimited: 'Too many sign-in attempts. Please wait a few minutes and try again.',
  server: 'An unexpected error occurred. Please try again.',
  network: 'Network error. Please check your connection and try again.',
};

const MAX_MESSAGE_LENGTH = 200;

/** A backend message is shown only when it is a plain, non-empty, short string; anything else falls back to the default text. */
function safeMessage(message) {
  if (typeof message !== 'string') return '';
  const text = message.trim();
  return text && text.length <= MAX_MESSAGE_LENGTH ? text : '';
}

/**
 * @param {Object} params
 * @param {boolean} [params.ok=false] - response.ok
 * @param {number} [params.status=0] - response.status
 * @param {Object|null} [params.data=null] - parsed JSON body of a successful, 429 or 5xx response
 * @param {boolean} [params.networkError=false] - the request itself failed
 * @returns {{kind: 'success'|'verification'|'invalid'|'rateLimited'|'server'|'network', message: string}}
 */
export function getLoginOutcome({ ok = false, status = 0, data = null, networkError = false } = {}) {
  if (networkError) return { kind: 'network', message: LOGIN_MESSAGES.network };
  if (ok) {
    if (data?.emailVerificationRequired) return { kind: 'verification', message: '' };
    if (data?.token) return { kind: 'success', message: '' };
    return { kind: 'server', message: LOGIN_MESSAGES.server };
  }
  if (status === 429) return { kind: 'rateLimited', message: safeMessage(data?.message) || LOGIN_MESSAGES.rateLimited };
  if (status === 401) return { kind: 'invalid', message: LOGIN_MESSAGES.invalid };
  // The server's own fixed message for a failure it understands (e.g. the verification email could not be sent: {success:false, message}).
  // Only that exact shape is shown; any other server error keeps the generic text.
  if (status >= 500 && data?.success === false && safeMessage(data.message)) return { kind: 'server', message: safeMessage(data.message) };
  return { kind: 'server', message: LOGIN_MESSAGES.server };
}
