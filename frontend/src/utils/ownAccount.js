import { isCurrentUser } from '../components/users/userListUtils.js';

/**
 * Nobody deactivates their own account. The server refuses it on every route (PATCH /api/staff/{id}/deactivate, PUT /api/staff/{id}
 * with active=false, PATCH /api/users/{id}/deactivate); these helpers only stop the screens from offering it, for convenience.
 * Nothing here is a security control.
 */
export const OWN_ACCOUNT_DEACTIVATE_HINT = 'You cannot deactivate your own account.';

/** True when the account (a Staff record or a user, both carry `username`) may be offered "Deactivate" / "Inactive" to the signed-in user. */
export function canOfferDeactivation(account, currentUsername) {
  return !isCurrentUser(account, currentUsername);
}
