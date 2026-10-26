import React, { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { EyeIcon, EyeOffIcon, CloseIcon } from '../icons/Icons';
import { createStaff } from '../../services/api';
import { evaluatePasswordRules } from '../../utils/passwordPolicy';
import PasswordRequirements from '../common/PasswordRequirements';
import DepartmentOptions from '../common/DepartmentOptions';
import useDepartments from '../../hooks/useDepartments';

const initialFormState = {
  fullName: '',
  email: '',
  phone: '',
  department: 'HOUSEKEEPING',
  availability: 'AVAILABLE',
  username: '',
  temporaryPassword: '',
};

/**
 * AddStaffModal — modal for creating a new staff member with temporary credentials.
 *
 * Props:
 *   isOpen    – boolean indicating if modal is open
 *   onClose   – callback to close the modal
 *   onSuccess – callback(newStaff, fullName) after successful creation
 */
// title / description / submitLabel: optional, so the same form can be opened as "Add User" from the Users page
export default function AddStaffModal({ isOpen, onClose, onSuccess, title = 'Add New Staff', description = null, submitLabel = 'Add Staff' }) {
  const { departments, activeDepartments } = useDepartments();
  const navigate = useNavigate();
  const [formData, setFormData] = useState(initialFormState);
  const [showTemporaryPassword, setShowTemporaryPassword] = useState(false);
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // If the default department cannot take new staff (for example it is inactive), start on the first one that can
  useEffect(() => {
    if (activeDepartments.length > 0 && !activeDepartments.some((department) => department.code === formData.department)) {
      setFormData((prev) => ({ ...prev, department: activeDepartments[0].code }));
    }
  }, [activeDepartments, formData.department]);

  if (!isOpen) return null;

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
    if (formError) setFormError('');
  };

  const handleClose = () => {
    if (isSubmitting) return;
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
        department: formData.department,
        availability: formData.availability,
        username: formData.username.trim(),
        temporaryPassword: formData.temporaryPassword,
      };

      const newStaff = await createStaff(payload);

      setFormData(initialFormState);
      setShowTemporaryPassword(false);
      setFormError('');
      onSuccess(newStaff, payload.fullName);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }

      if (err.status === 409) {
        setFormError(
          err.message ||
            'Username or email already exists. Please choose a different one.'
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
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-fadeIn"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] overflow-y-auto border border-gray-100"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-6 border-b border-gray-100 flex justify-between items-center gap-4">
          <div className="min-w-0">
            <h2 className="text-xl font-bold text-gray-900">{title}</h2>
            {description && <p className="text-sm text-gray-500 mt-1">{description}</p>}
          </div>
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
                <label className="text-sm font-medium text-gray-700">Email *</label>
                <input
                  type="email"
                  name="email"
                  required
                  value={formData.email}
                  onChange={handleInputChange}
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                  placeholder="john@example.com"
                />
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
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800 bg-white"
                >
                  <DepartmentOptions departments={departments} activeOnly />
                </select>
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

              {/* Status (Default Active) */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">Status</label>
                <input
                  type="text"
                  disabled
                  value="Active (Default)"
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg bg-gray-50 text-gray-500 cursor-not-allowed text-sm"
                />
              </div>

              {/* Username */}
              <div className="space-y-1">
                <label className="text-sm font-medium text-gray-700">
                  Username *
                </label>
                <input
                  type="text"
                  name="username"
                  required
                  value={formData.username}
                  onChange={handleInputChange}
                  disabled={isSubmitting}
                  className="w-full px-4 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                  placeholder="johndoe"
                />
              </div>

              {/* Temporary Password */}
              <div className="space-y-2">
                <label className="text-sm font-medium text-gray-700">
                  Temporary Password *
                </label>
                <div className="relative">
                  <input
                    type={showTemporaryPassword ? 'text' : 'password'}
                    name="temporaryPassword"
                    required
                    value={formData.temporaryPassword}
                    onChange={handleInputChange}
                    disabled={isSubmitting}
                    className="w-full px-4 py-2 pr-10 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800"
                    placeholder="••••••••"
                  />
                  <button
                    type="button"
                    onClick={() => setShowTemporaryPassword(!showTemporaryPassword)}
                    tabIndex={-1}
                    className="absolute inset-y-0 right-0 pr-3 flex items-center text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
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
                </div>
                <PasswordRequirements rules={tempPasswordRules} />
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
