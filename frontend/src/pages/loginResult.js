/**
 * Login outcome classification for LoginPage. Pure, so it can be tested without a DOM.
 *
 * Only a backend 401 counts as invalid credentials; server errors and network failures get their
 * own messages so they are never reported as a wrong username or password. The invalid-credentials
 * message never says which of the two was wrong.
 */
export const LOGIN_MESSAGES = {
  invalid: 'Invalid username/email or password. Please try again.',
  server: 'An unexpected error occurred. Please try again.',
  network: 'Network error. Please check your connection and try again.',
};

/**
 * @param {Object} params
 * @param {boolean} [params.ok=false] - response.ok
 * @param {number} [params.status=0] - response.status
 * @param {Object|null} [params.data=null] - parsed JSON body of a successful response
 * @param {boolean} [params.networkError=false] - the request itself failed
 * @returns {{kind: 'success'|'verification'|'invalid'|'server'|'network', message: string}}
 */
export function getLoginOutcome({ ok = false, status = 0, data = null, networkError = false } = {}) {
  if (networkError) return { kind: 'network', message: LOGIN_MESSAGES.network };
  if (ok) {
    if (data?.emailVerificationRequired) return { kind: 'verification', message: '' };
    if (data?.token) return { kind: 'success', message: '' };
    return { kind: 'server', message: LOGIN_MESSAGES.server };
  }
  if (status === 401) return { kind: 'invalid', message: LOGIN_MESSAGES.invalid };
  return { kind: 'server', message: LOGIN_MESSAGES.server };
}
