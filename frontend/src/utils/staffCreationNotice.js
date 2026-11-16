// What the creator is told after an account has been created: the account always exists at this point, and the server says whether the
// invitation email (username, temporary password, login link) went out. Pure, so it can be tested without a DOM.

/**
 * @param {string} base the message that was shown before invitation emails existed (for example 'Staff member "Mia" created successfully!')
 * @param {{email?: string, invitationEmailSent?: boolean}|null} created the server's answer to the create request
 * @returns {string} the message to show: the account is never reported as failed, and the invitation is never claimed unless it was sent
 */
export function withInvitationOutcome(base, created) {
  const sent = created?.invitationEmailSent;
  if (sent === true) {
    const to = typeof created.email === 'string' && created.email.trim() ? created.email.trim() : 'their email address';
    return `${base} An invitation email with the username, temporary password and login link was sent to ${to}.`;
  }
  if (sent === false) {
    return `${base} However, the invitation email could not be sent. Please give them the username and temporary password another way.`;
  }
  return base; // an older server that does not say: nothing is claimed either way
}
