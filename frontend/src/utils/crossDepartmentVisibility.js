/*
 * What the Workload page shows of Cross-Department Support, depending on the System Settings switch.
 *
 *   setting on (or not readable)  → 'full'    the whole panel, as it works today (a failed read never hides it: the server still refuses
 *                                             new support on its own when the switch is off)
 *   setting off, support active   → 'manage'  a compact section with only the arrangements still in progress, so Admins and Managers can
 *                                             follow and end them; nothing new can be planned or confirmed
 *   setting off, nothing active   → 'hidden'  no panel at all (no notice, no summary cards, no departments list, no history)
 *   not known yet                 → 'pending' nothing yet, so the full panel never flashes before it is hidden
 *
 * Switching the setting off never ends, cancels or changes an arrangement: they stay active until they are completed or ended.
 */

/** How often the panel re-reads the switch (and, while it is off, the arrangements) while the page is open and visible. */
export const CROSS_DEPARTMENT_REFRESH_MS = 30000;

/** The support records still in progress, from a loaded history ({ status, data }); none while it is loading or failed. */
export function activeSupportRows(history) {
  if (history?.status !== 'ready' || !Array.isArray(history.data)) return [];
  return history.data.filter((row) => row?.status === 'ACTIVE');
}

/**
 * 'pending' | 'full' | 'manage' | 'hidden', from the switch ('loading' | 'on' | 'off' | 'unknown'), the support history, and whether an
 * end-of-support result is still on screen (keepOutcome: the section stays until that message is dismissed, even after the last
 * arrangement ended).
 */
export function supportPanelMode({ assistance, history, keepOutcome = false }) {
  if (assistance === 'loading') return 'pending';
  if (assistance !== 'off') return 'full';
  // Off: only arrangements already in progress are shown. If the history could not be read, whether any is active is unknown, so the
  // management section stays (with the error and a refresh) rather than hiding support that may need managing.
  if (history?.status === 'error') return 'manage';
  if (history?.status !== 'ready') return keepOutcome ? 'manage' : 'pending';
  return activeSupportRows(history).length > 0 || keepOutcome ? 'manage' : 'hidden';
}

/** Whether a new arrangement may be planned, previewed or confirmed from the page (the server checks again either way). */
export const canCreateSupport = (assistance) => assistance !== 'off';
