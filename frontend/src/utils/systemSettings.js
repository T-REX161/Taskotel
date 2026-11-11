/**
 * System Settings (Settings > System Settings, Admin only): the pure logic behind the page. The server owns the truth; this module only
 * checks what is typed, shapes the request, and reads the answer strictly, so that a failed or unreadable load is never shown as the
 * defaults (the page would then look like saved values and a Save could overwrite the real ones).
 */
import { getSystemSettings, updateSystemSettings } from '../services/api.js';

export const THRESHOLD_MIN = 1;
export const THRESHOLD_MAX = 100;

export const SYSTEM_SETTINGS_MESSAGES = {
  loading: 'Loading system settings…',
  loadFailed: 'Could not load the system settings. Your saved settings were not changed. Please try again.',
  saving: 'Saving…',
  saved: 'System settings saved.',
  saveFailed: 'Could not save the system settings. Nothing was changed. Please try again.',
  forbidden: 'Only administrators can view or change the system settings.',
  invalidThreshold: `Enter a whole number from ${THRESHOLD_MIN} to ${THRESHOLD_MAX}.`,
};

const MAX_MESSAGE_LENGTH = 200;

/** A threshold typed by the user: digits only, a whole number from 1 to 100. Decimals, blanks, signs, exponents and text are refused. */
export function parseThreshold(text) {
  const value = typeof text === 'number' ? String(text) : text;
  if (typeof value !== 'string' || !/^\d{1,3}$/.test(value.trim())) return { valid: false, message: SYSTEM_SETTINGS_MESSAGES.invalidThreshold };
  const number = Number(value.trim());
  if (number < THRESHOLD_MIN || number > THRESHOLD_MAX) return { valid: false, message: SYSTEM_SETTINGS_MESSAGES.invalidThreshold };
  return { valid: true, value: number };
}

/**
 * The server's answer as settings values, or null when it is not a complete, valid answer (a missing field, a non-boolean switch, a
 * threshold that is not a whole number from 1 to 100). null is treated as a failed load: nothing is guessed or defaulted.
 */
export function fromServer(response) {
  if (!response || typeof response !== 'object') return null;
  const { autoBalancingEnabled, capacityAlertEnabled, crossDepartmentAssistanceEnabled, capacityThresholdPercent } = response;
  if (![autoBalancingEnabled, capacityAlertEnabled, crossDepartmentAssistanceEnabled].every((v) => typeof v === 'boolean')) return null;
  if (!Number.isInteger(capacityThresholdPercent) || capacityThresholdPercent < THRESHOLD_MIN || capacityThresholdPercent > THRESHOLD_MAX) return null;
  return {
    autoBalancingEnabled,
    capacityAlertEnabled,
    crossDepartmentAssistanceEnabled,
    capacityThresholdPercent,
    updatedBy: typeof response.updatedBy === 'string' && response.updatedBy ? response.updatedBy : null,
  };
}

/** Saved settings as the editable form (the threshold is text while it is being typed). */
export function toForm(settings) {
  return {
    autoBalancingEnabled: settings.autoBalancingEnabled,
    capacityAlertEnabled: settings.capacityAlertEnabled,
    crossDepartmentAssistanceEnabled: settings.crossDepartmentAssistanceEnabled,
    capacityThresholdPercent: String(settings.capacityThresholdPercent),
  };
}

/** The save request from the form: all four fields, or an error (and nothing to send) when the threshold is not valid. */
export function buildRequest(form) {
  const threshold = parseThreshold(form.capacityThresholdPercent);
  if (!threshold.valid) return { error: threshold.message };
  return {
    request: {
      autoBalancingEnabled: Boolean(form.autoBalancingEnabled),
      capacityAlertEnabled: Boolean(form.capacityAlertEnabled),
      crossDepartmentAssistanceEnabled: Boolean(form.crossDepartmentAssistanceEnabled),
      capacityThresholdPercent: threshold.value,
    },
  };
}

/** Whether the form differs from what is saved (so Save is only offered for a real change). */
export function isDirty(saved, form) {
  if (!saved || !form) return false;
  const threshold = parseThreshold(form.capacityThresholdPercent);
  return (
    saved.autoBalancingEnabled !== form.autoBalancingEnabled ||
    saved.capacityAlertEnabled !== form.capacityAlertEnabled ||
    saved.crossDepartmentAssistanceEnabled !== form.crossDepartmentAssistanceEnabled ||
    !threshold.valid ||
    threshold.value !== saved.capacityThresholdPercent
  );
}

/** A message safe to show for a failed request: the server's own 400 text if it is short, a fixed one for 403, otherwise the fallback. */
export function systemSettingsErrorMessage(error, fallback) {
  if (error?.status === 403) return SYSTEM_SETTINGS_MESSAGES.forbidden;
  const text = typeof error?.message === 'string' ? error.message.trim() : '';
  return error?.status === 400 && text && text.length <= MAX_MESSAGE_LENGTH ? text : fallback;
}

/** Loads the saved settings. Rejects (never defaults) when the request fails or the answer is not a complete, valid one. */
export async function loadSystemSettings({ fetcher = getSystemSettings } = {}) {
  const settings = fromServer(await fetcher());
  if (!settings) throw Object.assign(new Error('Unexpected system settings answer'), { status: 0 });
  return settings;
}

/**
 * Saves the form and resolves with what the server confirmed. Rejects without sending anything when the threshold is invalid, and rejects
 * when the server refuses or answers with something unreadable, so a failure is never reported as a success.
 */
export async function saveSystemSettings(form, { send = updateSystemSettings } = {}) {
  const built = buildRequest(form);
  if (built.error) throw Object.assign(new Error(built.error), { status: 400 });
  const settings = fromServer(await send(built.request));
  if (!settings) throw Object.assign(new Error('Unexpected system settings answer'), { status: 0 });
  return settings;
}
