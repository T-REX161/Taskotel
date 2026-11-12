import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { CloseIcon } from '../icons/Icons';
import { updateStaff, requestStaffEmailChange, getUserRole } from '../../services/api';
import {
  SENT_MESSAGE,
  canRequestEmailChange,
  emailChangeErrorMessage,
  minutesLeft,
  pendingChange,
  pendingFromResponse,
  validateNewEmail,
} from '../../utils/loginEmailChange';
import DepartmentOptions from '../common/DepartmentOptions';
import { UNASSIGNED_MANAGER_NOTICE, canChooseNoDepartment, departmentPayload, departmentProblem, isUnassignedManager } from '../../utils/staffDepartment';
import useDepartments from '../../hooks/useDepartments';

/**
 * EditStaffModal — modal for updating an existing staff member's profile and active status.
 *
 * Props:
 *   isOpen    – boolean indicating if modal is open
 *   staff     – staff member object being edited (or null)
 *   onClose   – callback to close the modal
 *   onSuccess – callback(payload) after successful update
 *   onEmailChangeRequested – optional callback after a verification code was sent (the list can refresh its pending status)
 */
export default function EditStaffModal({ isOpen, staff, onClose, onSuccess, onEmailChangeRequested }) {
  const { departments } = useDepartments();
  const navigate = useNavigate();
  const [formData, setFormData] = useState({
    fullName: '',
    email: '',
    phone: '',
    department: '',
    availability: 'AVAILABLE',
    active: true,
    username: '',
  });
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  // Login email change: the address being proposed, what is pending, and the outcome of the last request
  const [newEmail, setNewEmail] = useState('');
  const [pending, setPending] = useState(null);
  const [emailBusy, setEmailBusy] = useState(false);
  const [emailError, setEmailError] = useState('');
  const [emailNotice, setEmailNotice] = useState('');

  useEffect(() => {
    if (staff) {
      setFormData({
        fullName: staff.fullName || staff.name || '',
        email: staff.email || '',
        phone: staff.phone || '',
        // A Staff member without a department opens as "None (Unassigned)"; nothing is invented for them
        department: staff.department || '',
        availability: staff.availability || 'AVAILABLE',
        active: staff.active !== undefined ? staff.active : true,
        username: staff.username || '',
      });
      setFormError('');
      setNewEmail('');
      setPending(pendingChange(staff));
      setEmailError('');
      setEmailNotice('');
    }
  }, [staff]);

  if (!isOpen || !staff) return null;

  // Only an Admin may assign, change or clear a department, on an account of any role; a Manager is shown theirs but cannot edit it
  const viewerRole = getUserRole();
  const allowNoDepartment = canChooseNoDepartment(viewerRole, staff.role || 'STAFF');
  const canEditDepartment = viewerRole === 'ADMIN';

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
    if (formError) setFormError('');
  };

  // Starts (or restarts) a verified change. The active login email is not touched: the staff member must confirm it themselves.
  const handleRequestEmailChange = async () => {
    if (emailBusy) return;
    setEmailNotice('');
    const problem = validateNewEmail(newEmail, staff.email);
    if (problem) {
      setEmailError(problem);
      return;
    }
    setEmailBusy(true);
    setEmailError('');
    try {
      const response = await requestStaffEmailChange(staff.id, newEmail.trim());
      setPending(pendingFromResponse(response));
      setNewEmail('');
      setEmailNotice(SENT_MESSAGE);
      if (onEmailChangeRequested) onEmailChangeRequested();
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setEmailError(emailChangeErrorMessage(err));
    } finally {
      setEmailBusy(false);
    }
  };

  const handleClose = () => {
    if (isSubmitting) return;
    setFormError('');
    onClose();
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;
    setFormError('');

    // Client-side validations
    if (!formData.fullName.trim()) {
      setFormError('Full name is required.');
      return;
    }
    const missingDepartment = departmentProblem({ viewerRole: getUserRole(), accountRole: staff.role || 'STAFF', department: formData.department });
    if (missingDepartment) {
      setFormError(missingDepartment);
      return;
    }

    setIsSubmitting(true);

    try {
      const payload = {
        fullName: formData.fullName.trim(),
        // The login email is shown but never edited here: the request carries the address the account already has, and the server refuses
        // any other. The staff member changes it themselves, with verification, from their own account settings.
        email: staff.email,
        phone: formData.phone.trim() || null,
        // null clears the department (Admin only, Staff accounts only; the server refuses it while the person has active assignments)
        department: departmentPayload(formData.department),
        availability: formData.availability,
        active: formData.active,
      };

      await updateStaff(staff.id, payload);

      setFormError('');
      onSuccess(payload);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }

      if (err.status === 409) {
        setFormError(
          err.message || 'A conflict occurred. Please check your data.'
        );
      } else if (err.status === 404) {
        setFormError(err.message || 'Staff member not found.');
      } else if (err.status === 403) {
        setFormError(
          'Permission denied. Only ADMIN or MANAGER users can perform this action.'
        );
      } else if (err.status === 400) {
        setFormError(
          err.message || 'Invalid input. Please check the form fields and try again.'
        );
      } else {
        setFormError(
          err.message || 'An error occurred while updating staff member. Please try again.'
        );
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-fadeIn"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] overflow-y-auto border border-gray-100"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-6 border-b border-gray-100 flex justify-between items-center">
          <h2 className="text-xl font-bold text-gray-900">Edit Staff</h2>
          <button
            type="button"
            onClick={handleClose}
            disabled={isSubmitting}
            className="text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
            aria-label="Close modal"
          >
            <CloseIcon className="w-6 h-6" />
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="p-6 space-y-4">
            {/* Form Error Banner */}
            {formError && (
              <div className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-2.5">
                <svg
                  className="w-5 h-5 flex-shrink-0 text-red-600 mt-0.5"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
                <span>{formError}</span>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* Full Name */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">
                  Full Name *
                </label>
                <input
                  type="text"
                  name="fullName"
                  required
                  value={formData.fullName}
                  onChange={handleInputChange}
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                  placeholder="e.g. John Doe"
                />
              </div>

              {/* Email */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">Login Email</label>
                <input
                  type="email"
                  name="email"
                  value={staff.email || ''}
                  readOnly
                  disabled
                  aria-describedby="login-email-note"
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg bg-gray-50 text-sm text-gray-500 cursor-not-allowed"
                />
                <p id="login-email-note" className="text-xs text-gray-500">
                  This is the verified login email. It is not changed by saving this form: a change must be verified with a code, and only the
                  staff member can confirm it.
                </p>
              </div>

              {/* Login email change */}
              <div className="space-y-2 md:col-span-2" data-section="login-email-change">
                {pending && (
                  <div role="status" className="p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-900 text-sm space-y-0.5">
                    <p className="font-semibold">Pending verification: {pending.masked}</p>
                    <p>
                      Not active yet. The login email is still {staff.email}. The staff member must sign in and enter the code sent to the new
                      address (expires in about {minutesLeft(pending.expiresAt)} min).
                    </p>
                  </div>
                )}
                {canRequestEmailChange(staff) ? (
                  <div className="space-y-1">
                    <label htmlFor="new-login-email" className="text-sm font-medium text-gray-700">
                      {pending ? 'Use a different address, or send a new code' : 'Change login email'}
                    </label>
                    <div className="flex flex-col sm:flex-row gap-2">
                      <input
                        id="new-login-email"
                        type="email"
                        value={newEmail}
                        onChange={(e) => { setNewEmail(e.target.value); if (emailError) setEmailError(''); }}
                        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleRequestEmailChange(); } }}
                        disabled={emailBusy || isSubmitting}
                        className="flex-1 px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                        placeholder="new.address@example.com"
                      />
                      <button
                        type="button"
                        onClick={handleRequestEmailChange}
                        disabled={emailBusy || isSubmitting}
                        className="px-4 py-2 rounded-lg border border-emerald-600 text-emerald-700 hover:bg-emerald-50 text-sm font-medium disabled:opacity-60 cursor-pointer"
                      >
                        {emailBusy ? 'Sending...' : pending ? 'Send new code' : 'Send verification code'}
                      </button>
                    </div>
                    {emailError && <p role="alert" className="text-xs text-red-700">{emailError}</p>}
                    {emailNotice && <p role="status" className="text-xs text-emerald-700">{emailNotice}</p>}
                  </div>
                ) : (
                  <p className="text-xs text-gray-500">A login email change can be requested for active Staff accounts only.</p>
                )}
              </div>

              {/* Phone */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">Phone</label>
                <input
                  type="tel"
                  name="phone"
                  value={formData.phone}
                  onChange={handleInputChange}
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                  placeholder="+1 234 567 8900"
                />
              </div>

              {/* Department */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">
                  Department *
                </label>
                <select
                  name="department"
                  value={formData.department}
                  onChange={handleInputChange}
                  disabled={isSubmitting || !canEditDepartment}
                  title={canEditDepartment ? undefined : 'Only administrators can change a department'}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800 bg-white disabled:bg-gray-50 disabled:text-gray-500"
                >
                  <DepartmentOptions departments={departments} activeOnly currentCode={staff?.department} allowNone={allowNoDepartment} />
                </select>
                {isUnassignedManager(staff) && (
                  <p role="status" data-testid="unassigned-manager-notice" className="text-xs text-amber-700">{UNASSIGNED_MANAGER_NOTICE}</p>
                )}
              </div>

              {/* Availability */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">
                  Availability *
                </label>
                <select
                  name="availability"
                  value={formData.availability}
                  onChange={handleInputChange}
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800 bg-white"
                >
                  <option value="AVAILABLE">Available</option>
                  <option value="UNAVAILABLE">Unavailable</option>
                </select>
              </div>

              {/* Status */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">Status</label>
                <select
                  name="active"
                  value={formData.active}
                  onChange={(e) =>
                    setFormData((prev) => ({
                      ...prev,
                      active: e.target.value === 'true',
                    }))
                  }
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800 bg-white"
                >
                  <option value="true">Active</option>
                  <option value="false">Inactive</option>
                </select>
              </div>

              {/* Username (Disabled in Edit Mode) */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">Username</label>
                <input
                  type="text"
                  name="username"
                  value={formData.username}
                  disabled
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg text-sm bg-gray-50 text-gray-500 cursor-not-allowed"
                />
              </div>
            </div>
          </div>

          <div className="p-6 border-t border-gray-100 flex justify-end space-x-3">
            <button
              type="button"
              onClick={handleClose}
              disabled={isSubmitting}
              className="px-4 py-2 text-gray-600 font-medium hover:bg-gray-50 rounded-lg transition-colors border border-gray-200 cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className={`px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-lg transition-colors flex items-center gap-2 cursor-pointer shadow-xs ${
                isSubmitting ? 'opacity-80 cursor-wait' : ''
              }`}
            >
              {isSubmitting ? (
                <>
                  <svg
                    className="animate-spin h-4 w-4 text-white"
                    fill="none"
                    viewBox="0 0 24 24"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    />
                  </svg>
                  <span>Updating Staff...</span>
                </>
              ) : (
                'Update Staff'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}
