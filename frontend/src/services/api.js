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

  const headers = {
    ...getAuthHeaders(),
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
 * Update an existing staff member.
 */
export const updateStaff = (id, request) => {
  return apiFetch(`/api/staff/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
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
 * Set permanent credentials using the one-time setupToken.
 * This does NOT use the standard JWT Bearer token and does NOT store the token.
 */
export const setupCredentials = async ({ setupToken, newUsername, newPassword, confirmPassword }) => {
  const response = await fetch('/api/auth/credentials/setup', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      setupToken,
      newUsername,
      newPassword,
      confirmPassword,
    }),
  });

  let data = null;
  try {
    data = await response.json();
  } catch (e) {
    // Response might not be JSON
  }

  if (!response.ok) {
    let errorMsg = data?.message || data?.error;
    if (errorMsg) {
      const lower = errorMsg.toLowerCase();
      if (lower.includes('invalid or expired setup token') || lower.includes('setup token')) {
        errorMsg = 'Your setup session is invalid or has expired. Please verify your email again.';
      } else if (lower.includes('already exists') || lower.includes('duplicate')) {
        errorMsg = 'This username is already taken. Please choose a different username.';
      } else if (lower.includes('password') && (lower.includes('characters') || lower.includes('uppercase') || lower.includes('digit') || lower.includes('special'))) {
        errorMsg = 'Password does not meet the security requirements.';
      } else if (lower.includes('not required') || lower.includes('already completed')) {
        errorMsg = 'Account setup has already been completed for this user. Please sign in.';
      }
    } else {
      if (response.status === 400) {
        errorMsg = 'Invalid request. Please verify your inputs and try again.';
      } else if (response.status >= 500) {
        errorMsg = 'A server error occurred. Please try again later.';
      } else {
        errorMsg = 'Failed to update credentials. Please try again.';
      }
    }

    const error = new Error(errorMsg);
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data;
};

/**
 * Request a password reset email.
 */
export const requestPasswordReset = async (email) => {
  const response = await fetch('/api/auth/password-reset/request', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ email }),
  });

  if (!response.ok) {
    let data = null;
    try {
      data = await response.json();
    } catch (e) {
      // Not JSON
    }
    const error = new Error(data?.message || 'Failed to request password reset. Please try again later.');
    error.status = response.status;
    throw error;
  }
  return null;
};

/**
 * Confirm a password reset using the emailed code.
 */
export const confirmPasswordReset = async ({ email, code, newPassword, confirmPassword }) => {
  const response = await fetch('/api/auth/password-reset/confirm', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      email,
      code,
      newPassword,
      confirmPassword,
    }),
  });

  if (!response.ok) {
    let data = null;
    try {
      data = await response.json();
    } catch (e) {
      // Not JSON
    }
    const error = new Error(data?.message || 'Failed to reset password. Please check your inputs and try again.');
    error.status = response.status;
    throw error;
  }
  return null;
};

