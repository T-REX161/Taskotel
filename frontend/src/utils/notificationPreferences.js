/**
 * Notification preferences: the five categories, and the pure logic behind the Settings card. Email is the only channel
 * (Taskotel has no in-app notification delivery).
 *
 * The server owns the truth (per user, from the token). This module only shapes it for the screen: a map of
 * category -> { email, locked }, a change request for one switch, and a loader that shares one request.
 * Security alerts are mandatory: they are always on, whatever a response or a click says.
 */
import { getNotificationPreferences, updateNotificationPreferences } from '../services/api.js';

export const NOTIFICATION_CATEGORIES = [
  { key: 'TASK_ASSIGNED', title: 'Task Assigned / Reassigned', description: 'Get notified when a task is assigned or reassigned to you.', defaults: { email: true } },
  { key: 'TASK_STATUS', title: 'Task Status Updates', description: "Get notified when an assigned task's status changes.", defaults: { email: false } },
  { key: 'WORKLOAD_ALERT', title: 'Workload Alerts', description: 'Get notified when your workload reaches HIGH or AT_RISK.', defaults: { email: true } },
  { key: 'TASK_REMINDER', title: 'Task Reminders', description: 'Receive reminders about pending or overdue tasks.', defaults: { email: false } },
  { key: 'SECURITY', title: 'Security Alerts', description: 'Receive important account and login security notifications.', defaults: { email: true }, locked: true },
];

export const CHANNELS = ['email'];

export const NOTIFICATION_MESSAGES = {
  loading: 'Loading your notification preferences…',
  loadFailed: 'Could not load your notification preferences. Please try again.',
  saving: 'Saving…',
  saved: 'Notification preferences saved.',
  saveFailed: 'Could not save your notification preference. Please try again.',
  securityRequired: 'Security alerts are always on to protect your account.',
};

const MAX_MESSAGE_LENGTH = 200;
const isLocked = (key) => NOTIFICATION_CATEGORIES.find((c) => c.key === key)?.locked === true;

/** The defaults as a map (what a user sees before choosing anything). */
export function defaultPreferenceMap() {
  return Object.fromEntries(NOTIFICATION_CATEGORIES.map((c) => [c.key, { ...c.defaults, locked: Boolean(c.locked) }]));
}

/** A server response (or nothing) as a complete map: missing categories get their defaults, security alerts are always on and locked. */
export function toPreferenceMap(response) {
  const map = defaultPreferenceMap();
  for (const item of response?.preferences || []) {
    if (!map[item.category]) continue;
    map[item.category] = { email: Boolean(item.email), locked: Boolean(item.locked) || isLocked(item.category) };
  }
  for (const category of NOTIFICATION_CATEGORIES) {
    if (category.locked) map[category.key] = { email: true, locked: true };
  }
  return map;
}

/** The map after switching one channel; a locked category (security alerts) or an unknown one is returned unchanged. */
export function withChange(map, category, channel, value) {
  if (!map[category] || map[category].locked || !CHANNELS.includes(channel)) return map;
  return { ...map, [category]: { ...map[category], [channel]: Boolean(value) } };
}

/** The request that saves one switch: that category with its email switch. null when the change is not allowed. */
export function changeRequest(map, category, channel, value) {
  const next = withChange(map, category, channel, value);
  if (next === map || !next[category]) return null;
  return { preferences: [{ category, email: next[category].email }] };
}

/** A message safe to show for a failed request: the server's own 400 text if it is short, otherwise the fallback. */
export function notificationErrorMessage(error, fallback) {
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  return error?.status === 400 && text && text.length <= MAX_MESSAGE_LENGTH ? text : fallback;
}

let inFlight = null;

/** Loads the signed-in user's preferences as a map. Overlapping calls (e.g. a double mount) share one request. */
export function loadNotificationPreferences({ fetcher = getNotificationPreferences } = {}) {
  if (inFlight) return inFlight;
  inFlight = Promise.resolve()
    .then(() => fetcher())
    .then(toPreferenceMap)
    .finally(() => { inFlight = null; });
  return inFlight;
}

/**
 * Saves one switch and resolves with the server's complete answer as a map. Rejects when the change is not allowed
 * (nothing is sent) or when the server refuses it, so a failure is never shown as a success.
 */
export async function saveNotificationPreference(map, category, channel, value, { send = updateNotificationPreferences } = {}) {
  const request = changeRequest(map, category, channel, value);
  if (!request) throw Object.assign(new Error(NOTIFICATION_MESSAGES.securityRequired), { status: 400 });
  return toPreferenceMap(await send(request));
}
