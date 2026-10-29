import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import {
  EyeIcon,
  EyeOffIcon,
  CloseIcon,
  UserPlusIcon,
  UserIcon,
  MailIcon,
  PhoneIcon,
  StaffIcon,
  DepartmentsIcon,
  CalendarIcon,
  BriefcaseIcon,
  SecurityIcon,
  LockIcon,
  CircleDotIcon,
} from '../icons/Icons';
import { createStaff, getUserRole } from '../../services/api';
import { evaluatePasswordRules } from '../../utils/passwordPolicy';
import PasswordRequirements from '../common/PasswordRequirements';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';
import useUsernameAvailability from '../../hooks/useUsernameAvailability';
import { generateUsername, validateUsername, USERNAME_INVALID_MESSAGES } from '../../utils/username';
import { generateTemporaryPassword, copyText } from '../../utils/passwordGenerator';
import UsernameFeedback from './UsernameFeedback';

const initialFormState = {
  fullName: '',
  email: '',
  phone: '',
  role: 'STAFF',
  department: 'HOUSEKEEPING',
  availability: 'AVAILABLE',
  username: '',
  temporaryPassword: '',
};

// Values are the backend Role names. Only an administrator may create manager or admin accounts (enforced by the server).
const ROLE_OPTIONS = [
  { value: 'STAFF', label: 'Staff' },
  { value: 'MANAGER', label: 'Manager' },
  { value: 'ADMIN', label: 'Admin' },
];

const INPUT_CLASS =
  'w-full h-10 pl-10 pr-4 bg-white border border-gray-200 rounded-lg text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent disabled:opacity-60';

const PASSWORD_ACTION_BUTTON =
  'h-9 flex items-center justify-center rounded-lg border border-gray-200 bg-white text-gray-500 hover:text-emerald-600 hover:bg-gray-50 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-white disabled:hover:text-gray-500';

/** A bordered form section with an icon, a title and a short subtitle. */
function FormSection({ icon: Icon, title, subtitle, aside, children }) {
  const content = (
    <div className="space-y-4 min-w-0">
      <div className="flex items-center gap-3">
        <div className="w-9 h-9 rounded-lg bg-emerald-50 text-emerald-700 flex items-center justify-center shrink-0">
          <Icon className="w-4 h-4" />
        </div>
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-gray-900 leading-6">{title}</h3>
          <p className="text-sm text-gray-500 leading-5">{subtitle}</p>
        </div>
      </div>
      {children}
    </div>
  );
  // aside (the password rules) sits beside the heading and fields on wide screens, below them on phones
  return (
    <section className="rounded-xl border border-gray-200 p-5">
      {aside ? <div className="grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,15rem)] gap-x-4 gap-y-4">{content}{aside}</div> : content}
    </section>
  );
}

/** A labelled field with an icon inside the left edge of its input or select. */
function Field({ label, htmlFor, icon: Icon, labelExtra, footer, children }) {
  return (
    <div className="space-y-1.5 min-w-0">
      <div className="flex items-center justify-between gap-2">
        <label htmlFor={htmlFor} className="block text-sm leading-5 font-medium text-gray-700">
          {label}
        </label>
        {labelExtra}
      </div>
      <div className="relative">
        <span className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <Icon className="w-4 h-4" />
        </span>
        {children}
      </div>
      {footer}
    </div>
  );
}

/**
 * AddStaffModal — modal for creating a new staff member with temporary credentials.
 *
 * Props:
 *   isOpen    – boolean indicating if modal is open
 *   onClose   – callback to close the modal
 *   onSuccess – callback(newStaff, fullName) after successful creation
 */
// title / description / submitLabel: optional, so the same form can be opened as "Add User" from the Users page
export default function AddStaffModal({
  isOpen,
  onClose,
  onSuccess,
  title = 'Add New Staff',
  description = 'Add staff member to your hotel team',
  submitLabel = 'Add Staff',
}) {
  const { departments, activeDepartments } = useDepartments();
  const navigate = useNavigate();
  const [formData, setFormData] = useState(initialFormState);
  const [showTemporaryPassword, setShowTemporaryPassword] = useState(false);
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  // Until the user types their own username it follows the full name; after that it is never overwritten
  const [usernameEdited, setUsernameEdited] = useState(false);
  const [recheckKey, setRecheckKey] = useState(0);
  // The temporary password follows the full name until the admin types their own or regenerates it
  const [passwordLocked, setPasswordLocked] = useState(false);
  const [copyState, setCopyState] = useState('idle');
  // The Copied / Copy failed message disappears by itself
  useEffect(() => {
    if (copyState === 'idle') return undefined;
    const timer = setTimeout(() => setCopyState('idle'), 2500);
    return () => clearTimeout(timer);
  }, [copyState]);

  // Managers can add staff only; administrators can also create manager and admin accounts
  const canChooseRole = getUserRole() === 'ADMIN';
  const roleOptions = canChooseRole ? ROLE_OPTIONS : ROLE_OPTIONS.filter((option) => option.value === 'STAFF');

  const usernameValue = formData.username.trim();
  const usernameInvalidReason = validateUsername(usernameValue);
  const checked = useUsernameAvailability(formData.username, formData.fullName, recheckKey);
  // Ignore a result that belongs to an older value of the field
  const usernameCheck = checked.username === usernameValue ? checked : { status: 'idle', suggestions: [] };
  const usernameStatus =
    usernameInvalidReason === 'empty' ? 'idle' : usernameInvalidReason !== 'ok' ? 'invalid' : usernameCheck.status === 'idle' ? 'checking' : usernameCheck.status;
  const feedback = { ...usernameCheck, status: usernameStatus, reason: usernameInvalidReason };
  const hasPickedAlternative = useRef(false);

  // A generated username that is already taken is replaced by the first alternative the server confirmed free
  useEffect(() => {
    if (!usernameEdited && usernameCheck.status === 'taken' && usernameCheck.suggestions.length > 0 && !hasPickedAlternative.current) {
      hasPickedAlternative.current = true;
      setFormData((prev) => (prev.username.trim() === checked.username ? { ...prev, username: usernameCheck.suggestions[0] } : prev));
    }
    if (usernameCheck.status !== 'taken') hasPickedAlternative.current = false;
  }, [usernameEdited, usernameCheck.status, usernameCheck.suggestions, checked.username]);

  // If the default department cannot take new staff (for example it is inactive), start on the first one that can
  useEffect(() => {
    if (activeDepartments.length > 0 && !activeDepartments.some((department) => department.code === formData.department)) {
      setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }));
    }
  }, [activeDepartments, formData.department]);

  if (!isOpen) return null;

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    if (name === 'username') setUsernameEdited(value !== '');
    if (name === 'temporaryPassword') setPasswordLocked(value !== '');
    if (name === 'temporaryPassword' || name === 'fullName') setCopyState('idle');
    setFormData((prev) => ({
      ...prev,
      [name]: value,
      ...(name === 'fullName' && !usernameEdited ? { username: generateUsername(value) } : {}),
      ...(name === 'fullName' && !passwordLocked ? { temporaryPassword: value.trim() ? generateTemporaryPassword(value) : '' } : {}),
    }));
    if (formError) setFormError('');
  };

  const regeneratePassword = () => {
    setPasswordLocked(true);
    setCopyState('idle');
    setFormData((prev) => ({ ...prev, temporaryPassword: generateTemporaryPassword(prev.fullName) }));
    if (formError) setFormError('');
  };

  const copyPassword = async () => {
    if (!formData.temporaryPassword) return;
    setCopyState((await copyText(formData.temporaryPassword)) ? 'copied' : 'failed');
  };

  const regenerateUsername = () => {
    setUsernameEdited(false);
    setFormData((prev) => ({ ...prev, username: generateUsername(prev.fullName) }));
    if (formError) setFormError('');
  };

  const pickUsername = (username) => {
    setUsernameEdited(true);
    setFormData((prev) => ({ ...prev, username }));
    if (formError) setFormError('');
  };

  const handleClose = () => {
    if (isSubmitting) return;
    setUsernameEdited(false);
    setPasswordLocked(false);
    setCopyState('idle');
    setFormData(initialFormState);
    setShowTemporaryPassword(false);
    setFormError('');
    onClose();
  };

  const tempPasswordRules = evaluatePasswordRules(formData.temporaryPassword);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;
    setFormError('');

    // Client-side validations
    if (!formData.fullName.trim()) {
      setFormError('Full name is required.');
      return;
    }
    if (!formData.email.trim()) {
      setFormError('Email is required.');
      return;
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(formData.email.trim())) {
      setFormError('Please enter a valid email address.');
      return;
    }
    if (!formData.username.trim()) {
      setFormError('Username is required.');
      return;
    }
    if (usernameInvalidReason !== 'ok') {
      setFormError(USERNAME_INVALID_MESSAGES[usernameInvalidReason]);
      return;
    }
    if (usernameStatus === 'taken') {
      setFormError('That username is already taken. Choose another one or pick a suggestion.');
      return;
    }
    if (!formData.temporaryPassword.trim()) {
      setFormError('Temporary password is required.');
      return;
    }
    if (!tempPasswordRules.isValid) {
      setFormError('Temporary password does not satisfy all required security rules.');
      return;
    }

    setIsSubmitting(true);

    try {
      const payload = {
        fullName: formData.fullName.trim(),
        email: formData.email.trim(),
        phone: formData.phone.trim() || null,
        role: canChooseRole ? formData.role : 'STAFF',
        department: formData.department,
        availability: formData.availability,
        username: formData.username.trim(),
        temporaryPassword: formData.temporaryPassword,
      };

      const newStaff = await createStaff(payload);

      setFormData(initialFormState);
      setUsernameEdited(false);
      setPasswordLocked(false);
      setCopyState('idle');
      setShowTemporaryPassword(false);
      setFormError('');
      onSuccess(newStaff, payload.fullName);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }

      if (err.status === 409) {
        // Taken between the live check and saving: check again so the field shows it and offers alternatives
        if (/username/i.test(err.message || '')) setRecheckKey((key) => key + 1);
        setFormError(
          err.message ||
            'Username or email already exists. Please choose a different one.'
        );
      } else if (err.status === 404) {
        setFormError(err.message || 'Staff member not found.');
      } else if (err.status === 403) {
        setFormError(
          formData.role !== 'STAFF'
            ? 'Permission denied. Only administrators can create manager or admin accounts.'
            : 'Permission denied. Only ADMIN or MANAGER users can perform this action.'
        );
      } else if (err.status === 400) {
        setFormError(
          err.message || 'Invalid input. Please check the form fields and try again.'
        );
      } else {
        setFormError(
          err.message ||
            'An error occurred while creating staff member. Please try again.'
        );
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-3 sm:p-4 animate-fadeIn"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-3xl max-h-[90vh] supports-[height:100dvh]:max-h-[90dvh] flex flex-col overflow-hidden border border-gray-100"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="add-staff-title"
      >
        {/* Header */}
        <div className="shrink-0 px-5 pt-5 pb-3 sm:px-6 flex items-start justify-between gap-4">
          <div className="flex items-center gap-3 sm:gap-4 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-emerald-50 text-emerald-700 flex items-center justify-center shrink-0">
              <UserPlusIcon className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <h2 id="add-staff-title" className="text-lg sm:text-xl font-bold text-gray-900 leading-tight">{title}</h2>
              {description && <p className="text-sm text-gray-500 leading-5">{description}</p>}
            </div>
          </div>
          <button
            type="button"
            onClick={handleClose}
            disabled={isSubmitting}
            className="text-gray-400 hover:text-gray-600 transition-colors cursor-pointer shrink-0"
            aria-label="Close modal"
          >
            <CloseIcon className="w-6 h-6" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col flex-1 min-h-0">
          <div className="flex-1 min-h-0 overflow-y-auto px-5 pt-1 pb-5 sm:px-6 space-y-4">
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

            {/* 1. Basic Information: two columns */}
            <FormSection icon={UserIcon} title="Basic Information" subtitle="Enter the staff member's basic details">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-4">
                <Field label="Full Name *" htmlFor="add-staff-fullName" icon={UserIcon}>
                  <input
                    id="add-staff-fullName"
                    type="text"
                    name="fullName"
                    required
                    value={formData.fullName}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={INPUT_CLASS}
                    placeholder="e.g. John Doe"
                  />
                </Field>
                <Field label="Email *" htmlFor="add-staff-email" icon={MailIcon}>
                  <input
                    id="add-staff-email"
                    type="email"
                    name="email"
                    required
                    value={formData.email}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={INPUT_CLASS}
                    placeholder="john@example.com"
                  />
                </Field>
                <Field label="Phone" htmlFor="add-staff-phone" icon={PhoneIcon}>
                  <input
                    id="add-staff-phone"
                    type="tel"
                    name="phone"
                    value={formData.phone}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={INPUT_CLASS}
                    placeholder="+1 234 567 8900"
                  />
                </Field>
                <Field
                  label="Username *"
                  htmlFor="add-staff-username"
                  icon={UserIcon}
                  labelExtra={
                    <span className="flex items-center gap-2">
                      {formData.username && (
                        <span
                          className={`px-1.5 rounded text-[11px] leading-4 font-medium ${
                            usernameEdited ? 'bg-gray-100 text-gray-600' : 'bg-emerald-50 text-emerald-700'
                          }`}
                        >
                          {usernameEdited ? 'Custom' : 'Suggested'}
                        </span>
                      )}
                      <button
                        type="button"
                        onClick={regenerateUsername}
                        disabled={isSubmitting || !generateUsername(formData.fullName)}
                        title="Generate username from full name"
                        aria-label="Generate username from full name"
                        className="text-gray-400 hover:text-emerald-600 transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                      >
                        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                        </svg>
                      </button>
                    </span>
                  }
                  footer={<UsernameFeedback check={feedback} onPickSuggestion={pickUsername} disabled={isSubmitting} />}
                >
                  <input
                    id="add-staff-username"
                    type="text"
                    name="username"
                    required
                    value={formData.username}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={INPUT_CLASS}
                    placeholder="johndoe"
                  />
                </Field>
              </div>
            </FormSection>

            {/* 2. Work Information: role, department and availability in one row on wide screens */}
            <FormSection icon={BriefcaseIcon} title="Work Information" subtitle="Set role, department and availability">
              <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-4">
                <Field label="Role *" htmlFor="add-staff-role" icon={StaffIcon}>
                  <select
                    id="add-staff-role"
                    name="role"
                    value={canChooseRole ? formData.role : 'STAFF'}
                    onChange={handleInputChange}
                    disabled={isSubmitting || !canChooseRole}
                    className={`${INPUT_CLASS} cursor-pointer`}
                    title={canChooseRole ? undefined : 'Only administrators can add managers or admins'}
                  >
                    {roleOptions.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Department *" htmlFor="add-staff-department" icon={DepartmentsIcon}>
                  <select
                    id="add-staff-department"
                    name="department"
                    value={formData.department}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={`${INPUT_CLASS} cursor-pointer`}
                  >
                    <DepartmentOptions departments={departments} activeOnly />
                  </select>
                </Field>
                <Field label="Availability *" htmlFor="add-staff-availability" icon={CalendarIcon}>
                  <select
                    id="add-staff-availability"
                    name="availability"
                    value={formData.availability}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className={`${INPUT_CLASS} cursor-pointer`}
                  >
                    <option value="AVAILABLE">Available</option>
                    <option value="UNAVAILABLE">Unavailable</option>
                  </select>
                </Field>
              </div>
            </FormSection>

            {/* 3. Account Settings: status and password, with the requirements beside them on wide screens */}
            <FormSection icon={SecurityIcon} title="Account Settings" subtitle="Set account status and temporary password" aside={<PasswordRequirements rules={tempPasswordRules} />}>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-4">
                  <Field label="Status" htmlFor="add-staff-status" icon={CircleDotIcon}>
                    <input
                      id="add-staff-status"
                      type="text"
                      readOnly
                      disabled
                      value="Active (Default)"
                      title="New accounts are always created active"
                      className={`${INPUT_CLASS} bg-gray-50 text-gray-500 cursor-not-allowed`}
                    />
                  </Field>
                  <Field
                    label="Temporary Password *"
                    htmlFor="add-staff-password"
                    icon={LockIcon}
                    labelExtra={
                      <span role="status" aria-live="polite" className={`text-[11px] leading-4 font-medium ${copyState === 'failed' ? 'text-red-600' : 'text-emerald-700'}`}>
                        {copyState === 'copied' ? 'Copied' : copyState === 'failed' ? 'Copy failed' : ''}
                      </span>
                    }
                    footer={
                      <div className="grid grid-cols-2 gap-2">
                        <button
                          type="button"
                          onClick={copyPassword}
                          disabled={isSubmitting || !formData.temporaryPassword}
                          title="Copy password"
                          aria-label="Copy password"
                          className={PASSWORD_ACTION_BUTTON}
                        >
                          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                          </svg>
                        </button>
                        <button
                          type="button"
                          onClick={regeneratePassword}
                          disabled={isSubmitting}
                          title="Regenerate password"
                          aria-label="Regenerate password"
                          className={PASSWORD_ACTION_BUTTON}
                        >
                          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                          </svg>
                        </button>
                      </div>
                    }
                  >
                    <input
                      id="add-staff-password"
                      type={showTemporaryPassword ? 'text' : 'password'}
                      name="temporaryPassword"
                      required
                      value={formData.temporaryPassword}
                      onChange={handleInputChange}
                      disabled={isSubmitting}
                      className={`${INPUT_CLASS} pr-11`}
                      placeholder="••••••••"
                    />
                    <button
                      type="button"
                      onClick={() => setShowTemporaryPassword(!showTemporaryPassword)}
                      title={showTemporaryPassword ? 'Hide password' : 'Show password'}
                      className="absolute inset-y-0 right-1.5 my-auto w-7 h-7 flex items-center justify-center rounded-md text-gray-400 hover:text-gray-600 transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                      aria-label={
                        showTemporaryPassword ? 'Hide password' : 'Show password'
                      }
                    >
                      {showTemporaryPassword ? (
                        <EyeOffIcon className="w-4 h-4" />
                      ) : (
                        <EyeIcon className="w-4 h-4" />
                      )}
                    </button>
                  </Field>
                </div>
            </FormSection>
          </div>

          <div className="shrink-0 px-5 py-3.5 sm:px-6 border-t border-gray-100 flex flex-wrap items-center justify-end gap-3">
            {/* Shown beside the buttons (above them on phones) so it adds no height to the form */}
            {formData.role !== 'STAFF' && (
              <p className="basis-full sm:basis-auto sm:flex-1 min-w-0 text-xs text-gray-500">
                Managers and admins skip email verification and sign in with this password.
              </p>
            )}
            <button
              type="button"
              onClick={handleClose}
              disabled={isSubmitting}
              className="px-5 py-2 text-gray-700 font-medium hover:bg-gray-50 rounded-lg transition-colors border border-gray-200 cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className={`px-5 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-lg transition-colors flex items-center gap-2 cursor-pointer shadow-xs ${
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
                  <span>{submitLabel === 'Add Staff' ? 'Adding Staff...' : 'Adding...'}</span>
                </>
              ) : (
                submitLabel
              )}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}
