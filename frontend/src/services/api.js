/**
 * API utility for making authenticated requests to the Taskotel backend.
 */

import { registerDepartments, registerDepartmentNames } from '../utils/departmentRegistry.js';

export const getToken = () => localStorage.getItem('token');
export const getUserRole = () => localStorage.getItem('role');
export const getUsername = () => localStorage.getItem('username');

export const clearAuth = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  localStorage.removeItem('role');
};

export const getAuthHeaders = () => {
  const token = getToken();
  const headers = {
    'Content-Type': 'application/json',
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
};

/**
 * Fetch wrapper that attaches the JWT Bearer token and handles 401 redirects.
 */
export const apiFetch = async (url, options = {}) => {
  const token = getToken();

  if (!token) {
    clearAuth();
    window.location.href = '/login';
    throw new Error('No authentication token found. Please sign in.');
  }

  const baseHeaders = getAuthHeaders();
  // A file upload (FormData) must not carry the JSON content type: the browser sets multipart/form-data with its boundary
  if (typeof FormData !== 'undefined' && options.body instanceof FormData) {
    delete baseHeaders['Content-Type'];
  }
  const headers = {
    ...baseHeaders,
    ...(options.headers || {}),
  };

  try {
    const response = await fetch(url, {
      ...options,
      headers,
    });

    if (response.status === 401) {
      clearAuth();
      window.location.href = '/login';
      throw new Error('Session expired or unauthorized. Redirecting to login...');
    }

    if (!response.ok) {
      let errorData = null;
      try {
        errorData = await response.json();
      } catch (e) {
        // Response might not be JSON
      }

      let errorMsg = errorData?.message || errorData?.error;
      if (!errorMsg) {
        if (response.status === 403) {
          errorMsg = 'You do not have permission to perform this action.';
        } else if (response.status === 409) {
          errorMsg = 'A record with this username or email already exists.';
        } else if (response.status >= 500) {
          errorMsg = 'A server error occurred. Please try again later.';
        } else {
          errorMsg = 'Failed to process request. Please try again.';
        }
      }

      const err = new Error(errorMsg);
      err.status = response.status;
      err.data = errorData;
      throw err;
    }

    if (response.status === 204) {
      return null;
    }

    return await response.json();
  } catch (error) {
    if (error.message && error.message.includes('Redirecting to login')) {
      throw error;
    }
    throw error;
  }
};

/**
 * Get dashboard summary for the current user.
 * Supports both ADMIN/MANAGER and STAFF responses.
 */
export const getDashboardSummary = () => {
  return apiFetch('/api/dashboard/summary');
};

/**
 * Get all departments with staff and active task metrics.
 */
export const getDepartments = async () => {
  const departments = await apiFetch('/api/departments');
  registerDepartments(departments);
  return departments;
};

/**
 * Create a department (ADMIN or MANAGER).
 * Request: { name, description?, iconKey?, colorKey?, headStaffId?, active? }
 */
export const createDepartment = async (request) => {
  const department = await apiFetch('/api/departments', {
    method: 'POST',
    body: JSON.stringify(request),
  });
  registerDepartments([department]);
  return department;
};

/**
 * Update a department by its code (ADMIN or MANAGER). The code never changes.
 * Request: { name, description?, iconKey?, colorKey?, headStaffId?, active }
 */
export const updateDepartment = async (code, request) => {
  const department = await apiFetch(`/api/departments/${encodeURIComponent(code)}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
  registerDepartments([department]);
  return department;
};

/**
 * Remove a department (ADMIN or MANAGER). The server refuses with 409 and an explanation while staff or tasks
 * still belong to it, or for a built-in department; nothing else is changed.
 */
export const deleteDepartment = (code) => {
  return apiFetch(`/api/departments/${encodeURIComponent(code)}`, {
    method: 'DELETE',
  });
};

/**
 * Get the list of all tasks.
 */
export const getTasks = async () => {
  const tasks = await apiFetch('/api/tasks');
  registerDepartmentNames(tasks);
  return tasks;
};

/**
 * Create a new task.
 */
export const createTask = (request) => {
  return apiFetch('/api/tasks', {
    method: 'POST',
    body: JSON.stringify(request),
  });
};

/**
 * Update an existing task.
 */
export const updateTask = (id, request) => {
  return apiFetch(`/api/tasks/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
};

/**
 * Delete a task.
 */
export const deleteTask = (id) => {
  return apiFetch(`/api/tasks/${id}`, {
    method: 'DELETE',
  });
};

/**
 * Get the list of all task assignments.
 */
export const getAssignments = () => {
  return apiFetch('/api/assignments');
};

/**
 * Create a new task assignment.
 */
export const createAssignment = (request) => {
  return apiFetch('/api/assignments', {
    method: 'POST',
    body: JSON.stringify(request),
  });
};

/**
 * Reassign an existing assignment to a different staff member.
 */
export const reassignTask = (assignmentId, request) => {
  return apiFetch(`/api/assignments/${assignmentId}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
};

/**
 * Get the list of all users.
 * Requires ADMIN role.
 */
export const getUsers = () => {
  return apiFetch('/api/users');
};

/**
 * The signed-in user's own profile: { username, role, fullName, profileImageUrl }. Any role.
 */
export const getProfile = () => {
  return apiFetch('/api/profile');
};

/**
 * Email change, step 1: send a six-digit code to the NEW address (and again to resend). Only the new address is sent: the server decides
 * whose account it is from the token. Resolves with { pendingEmailMasked, expiresInSeconds, resendAvailableInSeconds }; nothing about the
 * account changes yet.
 */
export const requestEmailChange = (newEmail, currentPassword) => {
  // The current password re-authenticates the signed-in user (the server checks it against the signed-in account only)
  return apiFetch('/api/profile/email-change/request', {
    method: 'POST',
    body: JSON.stringify({ newEmail, currentPassword }),
  });
};

/**
 * Email change, step 2: verify the code. Resolves with the updated profile (new email, verified) only if the server switched the address.
 */
export const confirmEmailChange = (code) => {
  return apiFetch('/api/profile/email-change/confirm', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
};

/**
 * Change the signed-in user's own display name. Only the name is sent: the server decides whose profile it is from the token.
 * Resolves with the updated profile.
 */
export const updatePhone = (phone) => {
  return apiFetch('/api/profile/phone', {
    method: 'PUT',
    body: JSON.stringify({ phone }),
  });
};

export const updateDisplayName = (displayName) => {
  return apiFetch('/api/profile/display-name', {
    method: 'PUT',
    body: JSON.stringify({ displayName }),
  });
};

/**
 * Change the signed-in user's own password: { currentPassword, newPassword, confirmPassword }. No user is named: the server uses the
 * token. Resolves with a confirmation message only when the password was really changed; nothing is stored in the browser.
 */
export const changePassword = (request) => {
  return apiFetch('/api/profile/password', {
    method: 'PUT',
    body: JSON.stringify(request),
  });
};

/**
 * The signed-in user's own notification preferences: { preferences: [{ category, email, locked }] }, one entry per
 * category, defaults filled in. The server decides whose they are from the token.
 */
export const getNotificationPreferences = () => {
  return apiFetch('/api/notification-preferences');
};

/**
 * Save changes to the signed-in user's notification preferences: { preferences: [{ category, email }] } (the categories to
 * change, each with both switches). Resolves with the complete current set, like getNotificationPreferences.
 */
export const updateNotificationPreferences = (request) => {
  return apiFetch('/api/notification-preferences', {
    method: 'PUT',
    body: JSON.stringify(request),
  });
};

/**
 * Save the signed-in user's own preferences: { themePreference, dateFormat, timeFormat }. The server decides whose they are from
 * the token. Resolves with the updated profile.
 */
export const updatePreferences = (preferences) => {
  return apiFetch('/api/profile/preferences', {
    method: 'PUT',
    body: JSON.stringify(preferences),
  });
};

/**
 * Upload a new profile picture for the signed-in user (multipart field "file"). The server keeps the current picture
 * until the new one is stored and saved. Resolves with the updated profile.
 */
export const uploadProfilePicture = (file) => {
  const body = new FormData();
  body.append('file', file);
  return apiFetch('/api/profile/picture', {
    method: 'POST',
    body,
  });
};

/**
 * Remove the signed-in user's profile picture. Resolves with the updated profile (profileImageUrl null).
 */
export const removeProfilePicture = () => {
  return apiFetch('/api/profile/picture', {
    method: 'DELETE',
  });
};

/**
 * Get the list of all staff members.
 */
export const getStaff = async () => {
  const staff = await apiFetch('/api/staff');
  registerDepartmentNames(staff);
  return staff;
};

/**
 * Create a new staff member.
 */
export const createStaff = (request) => {
  return apiFetch('/api/staff', {
    method: 'POST',
    body: JSON.stringify(request),
  });
};

/**
 * Whether a username is free. Resolves true only when the server says so; anything else throws.
 */
export const checkUsernameAvailability = async (username, signal) => {
  const result = await apiFetch(`/api/staff/username-availability?username=${encodeURIComponent(username)}`, { signal });
  if (typeof result?.available !== 'boolean') {
    throw new Error('Unexpected username availability response.');
  }
  return result.available;
};

/**
 * Update an existing staff member.
 */
export const updateStaff = (id, request) => {
  return apiFetch(`/api/staff/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  });
};

/**
 * Start a verified login email change for a staff member (Admin, or a Manager for their own department). A code goes to the new
 * address; the active login email is unchanged until the staff member confirms it themselves, signed in.
 */
export const requestStaffEmailChange = (id, newEmail) => {
  return apiFetch(`/api/staff/${id}/email-change`, {
    method: 'POST',
    body: JSON.stringify({ newEmail }),
  });
};

/**
 * Deactivate a staff member.
 */
export const deactivateStaff = (id) => {
  return apiFetch(`/api/staff/${id}/deactivate`, {
    method: 'PATCH',
  });
};

/**
 * Account-level actions (Admin only), by user id. They work whether or not the account has a staff record; for an account that has
 * one the server applies the staff rules too. The server decides who is calling from the token.
 */
export const deactivateUser = (id) => apiFetch(`/api/users/${id}/deactivate`, { method: 'PATCH' });
export const activateUser = (id) => apiFetch(`/api/users/${id}/activate`, { method: 'PATCH' });
export const removeUser = (id) => apiFetch(`/api/users/${id}`, { method: 'DELETE' });

/**
 * A Manager's department (Admin only; the server refuses everyone else). The department lives on the Manager's linked staff record.
 * assign: { department: <department code> }. The history is the audit trail of those changes, newest first.
 */
export const getManagerDepartment = (userId) => apiFetch(`/api/users/${userId}/manager-department`);
export const assignManagerDepartment = (userId, departmentCode) => apiFetch(`/api/users/${userId}/manager-department`, {
  method: 'PUT',
  body: JSON.stringify({ department: departmentCode }),
});
export const getManagerDepartmentHistory = (userId) => apiFetch(`/api/users/${userId}/manager-department/history`);

/**
 * Activate a staff member.
 */
export const activateStaff = (id) => {
  return apiFetch(`/api/staff/${id}/activate`, {
    method: 'PATCH',
  });
};

/**
 * Permanently remove an inactive staff member.
 * Requires ADMIN role.
 */
export const removeStaff = (id) => {
  return apiFetch(`/api/staff/${id}`, {
    method: 'DELETE',
  });
};

/**
 * Get current workload for all staff members computed on-the-fly.
 */
export const getWorkload = async () => {
  const workload = await apiFetch('/api/workload');
  registerDepartmentNames(workload);
  return workload;
};

/**
 * Read-only suggestions for lending staff between departments (ADMIN or MANAGER; STAFF receives 403).
 * Nothing is assigned or moved by this call.
 */
export const getWorkloadRecommendations = () => {
  return apiFetch('/api/workload/recommendations');
};

/**
 * Temporary cross-department support (ADMIN or MANAGER; STAFF receive 403).
 * Options: the waiting tasks of the receiving department and the staff who could help.
 */
export const getSupportOptions = (receivingDepartment) => {
  return apiFetch(`/api/support/options?receivingDepartment=${encodeURIComponent(receivingDepartment)}`);
};

/** Validates a plan and returns its workload impact. Changes nothing. Request: { receivingDepartment, items: [{ staffId, taskId }] } */
export const previewSupportPlan = (request) => {
  return apiFetch('/api/support/preview', { method: 'POST', body: JSON.stringify(request) });
};

/** Revalidates the plan on the server and, if everything still holds, creates the assignments. */
export const confirmSupportPlan = (request) => {
  return apiFetch('/api/support/assignments', { method: 'POST', body: JSON.stringify(request) });
};

/**
 * Ends one active support by hand (ADMIN or MANAGER). Resolves with the ended record; rejects with err.status 404 (gone),
 * 409 (already ended, in progress, or changed at the same moment; err.data.problems[0].code says which).
 */
export const endSupportAssignment = (supportId) => {
  return apiFetch(`/api/support/assignments/${encodeURIComponent(supportId)}/end`, { method: 'POST' });
};

/**
 * Removes one ended support record from the history list (ADMIN or MANAGER). The server archives it: the record is kept
 * but no longer listed. Rejects with err.status 409 (still active or already removed; err.data.problems[0].code says
 * which) or 404.
 */
export const archiveSupportAssignment = (supportId) => {
  return apiFetch(`/api/support/assignments/${encodeURIComponent(supportId)}/archive`, { method: 'POST' });
};

/** Support history, newest first. status: ACTIVE, COMPLETED or CANCELLED; omit for all. */
export const getSupportAssignments = (status) => {
  return apiFetch(`/api/support/assignments${status ? `?status=${encodeURIComponent(status)}` : ''}`);
};

/**
 * The public (no sign-in) authentication endpoints: credential setup, and password-reset request and confirm.
 * They share one way of turning a failed response into a message the page can show:
 *  - 429 (the backend rate limiter): its generic message when it is a usable string, otherwise a fixed fallback;
 *  - 5xx: a fixed message, never the response body (which could hold internal details);
 *  - other 4xx (validation): the backend's short message when it is a usable string, otherwise the caller's fallback;
 *  - a request that never got an answer: a fixed network message.
 * Reset codes, setup tokens, passwords and tokens are never put into an error message.
 */
export const RATE_LIMIT_MESSAGE = 'Too many requests. Please wait a few minutes and try again.';
export const SERVER_ERROR_MESSAGE = 'A server error occurred. Please try again later.';
export const NETWORK_ERROR_MESSAGE = 'Network error. Please check your connection and try again.';
const MAX_BACKEND_MESSAGE_LENGTH = 200;

/** A backend message is used only when it is a plain, non-empty, short string. */
export const usableBackendMessage = (data) => {
  const message = data && typeof data === 'object' ? data.message : undefined;
  if (typeof message !== 'string') return '';
  const text = message.trim();
  return text && text.length <= MAX_BACKEND_MESSAGE_LENGTH ? text : '';
};

/** True when the text repeats a non-trivial secret that was just sent (a token, code or password); such text is never displayed. */
const repeatsSecret = (text, secrets) => secrets.some((secret) => typeof secret === 'string' && secret.length >= 4 && text.includes(secret));

/**
 * The message to show for a failed public-auth response; `fallback` is used for a 4xx without a usable backend message.
 * `secrets` are values that were sent in the request: a backend message that contains one of them is not shown.
 */
export const publicAuthErrorMessage = (status, data, fallback, secrets = []) => {
  if (status >= 500 && status !== 429) return SERVER_ERROR_MESSAGE;
  const message = usableBackendMessage(data);
  const safe = message && !repeatsSecret(message, secrets) ? message : '';
  if (status === 429) return safe || RATE_LIMIT_MESSAGE;
  return safe || fallback;
};

/** The error thrown to the page. Only the status is kept besides the message: the response body is never attached. */
const publicAuthError = (message, status) => {
  const error = new Error(message);
  error.status = status;
  return error;
};

/** Sends a JSON request without the sign-in token and returns the response with its parsed body (null when there is none). */
const sendPublicAuthRequest = async (url, method, payload) => {
  let response;
  try {
    response = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
  } catch (e) {
    throw publicAuthError(NETWORK_ERROR_MESSAGE, 0);
  }
  let data = null;
  try {
    data = await response.json();
  } catch (e) {
    // Response might not be JSON
  }
  return { response, data };
};

/**
 * Set permanent credentials using the one-time setupToken.
 * This does NOT use the standard JWT Bearer token and does NOT store the token.
 */
export const setupCredentials = async ({ setupToken, newUsername, newPassword, confirmPassword }) => {
  const { response, data } = await sendPublicAuthRequest('/api/auth/credentials/setup', 'PUT', {
    setupToken,
    newUsername,
    newPassword,
    confirmPassword,
  });

  if (!response.ok) {
    if (response.status === 429 || response.status >= 500) {
      throw publicAuthError(publicAuthErrorMessage(response.status, data, '', [setupToken, newPassword, confirmPassword]), response.status);
    }
    // Known validation errors keep their friendly text. Anything else is shown only if it is a short, plain backend message that does
    // not repeat a secret that was just sent; otherwise the safe fallback is used. The raw body is never attached to the error.
    const recognised = [data?.message, data?.error].find((value) => typeof value === 'string' && value.trim());
    const lower = recognised ? recognised.toLowerCase() : '';
    let errorMsg;
    if (lower.includes('invalid or expired setup token') || lower.includes('setup token')) {
      errorMsg = 'Your setup session is invalid or has expired. Please verify your email again.';
    } else if (lower.includes('already exists') || lower.includes('duplicate')) {
      errorMsg = 'This username is already taken. Please choose a different username.';
    } else if (lower.includes('password') && (lower.includes('characters') || lower.includes('uppercase') || lower.includes('digit') || lower.includes('special'))) {
      errorMsg = 'Password does not meet the security requirements.';
    } else if (lower.includes('not required') || lower.includes('already completed')) {
      errorMsg = 'Account setup has already been completed for this user. Please sign in.';
    } else {
      const fallback = response.status === 400 ? 'Invalid request. Please verify your inputs and try again.' : 'Failed to update credentials. Please try again.';
      errorMsg = publicAuthErrorMessage(response.status, data, fallback, [setupToken, newPassword, confirmPassword]);
    }
    throw publicAuthError(errorMsg, response.status);
  }

  return data;
};

/**
 * Request a password reset email.
 */
export const requestPasswordReset = async (email) => {
  const { response, data } = await sendPublicAuthRequest('/api/auth/password-reset/request', 'POST', { email });

  if (!response.ok) {
    throw publicAuthError(publicAuthErrorMessage(response.status, data, 'Failed to request password reset. Please try again later.', [email]), response.status);
  }
  return null;
};

/**
 * Confirm a password reset using the emailed code.
 */
export const confirmPasswordReset = async ({ email, code, newPassword, confirmPassword }) => {
  const { response, data } = await sendPublicAuthRequest('/api/auth/password-reset/confirm', 'POST', {
    email,
    code,
    newPassword,
    confirmPassword,
  });

  if (!response.ok) {
    throw publicAuthError(publicAuthErrorMessage(response.status, data, 'Failed to reset password. Please check your inputs and try again.', [code, newPassword, confirmPassword]), response.status);
  }
  return null;
};
