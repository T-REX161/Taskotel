import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { EyeIcon, EyeOffIcon, LockIcon, ProfileIcon } from '../components/icons/Icons';
import EquiFlowLogo from '../components/brand/EquiFlowLogo';
import { setupCredentials } from '../services/api';
import { evaluatePasswordRules } from '../utils/passwordPolicy';
import PasswordRequirements from '../components/common/PasswordRequirements';

export default function SetCredentialsPage() {
  const navigate = useNavigate();
  const location = useLocation();

  // Retrieve setup token and optional username passed through router state
  const { setupToken, username: initialUsername } = location.state || {};

  const [newUsername, setNewUsername] = useState(initialUsername || '');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showNewPassword, setShowNewPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const [validationErrors, setValidationErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Evaluate password rules dynamically using shared policy
  const passwordRules = evaluatePasswordRules(newPassword);
  const isPasswordValid = passwordRules.isValid;

  // If user refreshes or visits directly without active setupToken in router state
  if (!setupToken) {
    return (
      <div className="min-h-screen bg-[#f2f6f3] text-gray-900 flex flex-col justify-center items-center p-4 sm:p-6 lg:p-8 relative selection:bg-emerald-100 selection:text-emerald-900">
        <div className="absolute top-1/4 -left-20 w-72 h-72 bg-emerald-200/30 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-teal-200/25 rounded-full blur-3xl pointer-events-none" />

        <div className="w-full max-w-md relative z-10">
          <div className="bg-white rounded-2xl sm:rounded-3xl border border-gray-200/80 shadow-xl shadow-emerald-950/5 p-7 sm:p-10 text-center">
            <div className="w-14 h-14 mx-auto rounded-2xl bg-amber-50 border border-amber-200 flex items-center justify-center text-amber-600 mb-4">
              <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            </div>

            <h1 className="text-xl font-bold text-gray-900 tracking-tight">
              Credential Setup Session Unavailable
            </h1>
            <p className="text-sm text-gray-500 mt-2 mb-6">
              Credential setup session unavailable. Please start the verification process again.
            </p>

            <button
              type="button"
              onClick={() => navigate('/login')}
              className="w-full py-3 px-4 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 transition-all flex items-center justify-center gap-2 cursor-pointer"
            >
              Back to Sign In
            </button>
          </div>

          <div className="text-center mt-6">
            <p className="text-xs text-gray-400">
              EquiFlow — Hospitality Workforce Operations &copy; {new Date().getFullYear()} • All rights reserved
            </p>
          </div>
        </div>
      </div>
    );
  }

  const validateForm = () => {
    const errors = {};

    if (!newUsername.trim()) {
      errors.username = 'Username is required.';
    }

    if (!newPassword) {
      errors.password = 'Password is required.';
    } else if (!isPasswordValid) {
      errors.password = 'Password does not satisfy all required security rules.';
    }

    if (!confirmPassword) {
      errors.confirmPassword = 'Confirm password is required.';
    } else if (newPassword !== confirmPassword) {
      errors.confirmPassword = 'Passwords do not match.';
    }

    setValidationErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (isSubmitting) return;

    setServerError('');
    setSuccessMessage('');

    if (!validateForm()) {
      return;
    }

    setIsSubmitting(true);

    try {
      await setupCredentials({
        setupToken,
        newUsername: newUsername.trim(),
        newPassword,
        confirmPassword,
      });

      setSuccessMessage('Account setup complete! Redirecting to sign in...');

      // Redirect to /login after brief feedback, prefilling the new username
      setTimeout(() => {
        navigate('/login', {
          replace: true,
          state: {
            verifiedMessage: 'Account setup complete. Please log in with your new credentials.',
            username: newUsername.trim(),
          },
        });
      }, 1200);
    } catch (err) {
      setServerError(err.message || 'Failed to update credentials. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f2f6f3] text-gray-900 flex flex-col justify-center items-center p-4 sm:p-6 lg:p-8 relative selection:bg-emerald-100 selection:text-emerald-900">
      {/* Decorative ambient background glows */}
      <div className="absolute top-1/4 -left-20 w-72 h-72 bg-emerald-200/30 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-teal-200/25 rounded-full blur-3xl pointer-events-none" />

      {/* Main Centered Setup Card */}
      <div className="w-full max-w-md relative z-10">
        <div className="bg-white rounded-2xl sm:rounded-3xl border border-gray-200/80 shadow-xl shadow-emerald-950/5 p-7 sm:p-10">
          {/* Header Branding */}
          <div className="flex flex-col items-center text-center mb-6">
            <EquiFlowLogo className="w-14 h-14 rounded-[20%] shadow-lg shadow-emerald-600/25 mb-4 transform transition-transform hover:scale-105 duration-200" />

            <div className="flex items-center gap-1.5">
              <span className="text-xl font-extrabold tracking-tight text-gray-900">
                Equi<span className="text-emerald-600">Flow</span>
              </span>
              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md bg-emerald-50 text-emerald-700 border border-emerald-200/60">
                Staff Portal
              </span>
            </div>

            <h1 className="text-2xl font-bold text-gray-900 tracking-tight mt-4">
              Set Up Your Account
            </h1>
            <p className="text-sm text-gray-500 mt-1.5 max-w-sm">
              Your email has been verified. Please create your permanent username and password.
            </p>
          </div>

          {/* Success Banner */}
          {successMessage && (
            <div className="mb-5 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-ping" />
              <span>{successMessage}</span>
            </div>
          )}

          {/* Server Error Banner */}
          {serverError && (
            <div className="mb-5 p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-2.5 animate-fadeIn">
              <svg className="w-4 h-4 text-red-500 mt-0.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <span>{serverError}</span>
            </div>
          )}

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            {/* New Username Field */}
            <div>
              <label
                htmlFor="newUsername"
                className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5"
              >
                New Username
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                  <ProfileIcon className="w-4 h-4" />
                </div>
                <input
                  id="newUsername"
                  name="newUsername"
                  type="text"
                  autoComplete="username"
                  disabled={isSubmitting || !!successMessage}
                  value={newUsername}
                  onChange={(e) => {
                    setNewUsername(e.target.value);
                    if (validationErrors.username) {
                      setValidationErrors((prev) => ({ ...prev, username: '' }));
                    }
                  }}
                  placeholder="Enter permanent username"
                  className={`w-full pl-10 pr-4 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-gray-900 placeholder-gray-400 rounded-xl border text-sm transition-all outline-none ${
                    validationErrors.username
                      ? 'border-red-300 focus:border-red-500 focus:ring-4 focus:ring-red-500/10'
                      : 'border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10'
                  }`}
                />
              </div>
              {validationErrors.username && (
                <p className="text-xs text-red-600 mt-1.5 font-medium">{validationErrors.username}</p>
              )}
            </div>

            {/* New Password Field */}
            <div>
              <label
                htmlFor="newPassword"
                className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5"
              >
                New Password
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                  <LockIcon className="w-4 h-4" />
                </div>
                <input
                  id="newPassword"
                  name="newPassword"
                  type={showNewPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  disabled={isSubmitting || !!successMessage}
                  value={newPassword}
                  onChange={(e) => {
                    setNewPassword(e.target.value);
                    if (validationErrors.password) {
                      setValidationErrors((prev) => ({ ...prev, password: '' }));
                    }
                  }}
                  placeholder="••••••••"
                  className={`w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-gray-900 placeholder-gray-400 rounded-xl border text-sm transition-all outline-none ${
                    validationErrors.password
                      ? 'border-red-300 focus:border-red-500 focus:ring-4 focus:ring-red-500/10'
                      : 'border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10'
                  }`}
                />
                <button
                  type="button"
                  onClick={() => setShowNewPassword(!showNewPassword)}
                  tabIndex={-1}
                  className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
                  aria-label={showNewPassword ? 'Hide password' : 'Show password'}
                >
                  {showNewPassword ? <EyeOffIcon className="w-4 h-4" /> : <EyeIcon className="w-4 h-4" />}
                </button>
              </div>
              {validationErrors.password && (
                <p className="text-xs text-red-600 mt-1.5 font-medium">{validationErrors.password}</p>
              )}
            </div>

            {/* Password Requirements Checklist */}
            <PasswordRequirements rules={passwordRules} />

            {/* Confirm Password Field */}
            <div>
              <label
                htmlFor="confirmPassword"
                className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5"
              >
                Confirm Password
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                  <LockIcon className="w-4 h-4" />
                </div>
                <input
                  id="confirmPassword"
                  name="confirmPassword"
                  type={showConfirmPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  disabled={isSubmitting || !!successMessage}
                  value={confirmPassword}
                  onChange={(e) => {
                    setConfirmPassword(e.target.value);
                    if (validationErrors.confirmPassword) {
                      setValidationErrors((prev) => ({ ...prev, confirmPassword: '' }));
                    }
                  }}
                  placeholder="••••••••"
                  className={`w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-gray-900 placeholder-gray-400 rounded-xl border text-sm transition-all outline-none ${
                    validationErrors.confirmPassword
                      ? 'border-red-300 focus:border-red-500 focus:ring-4 focus:ring-red-500/10'
                      : 'border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10'
                  }`}
                />
                <button
                  type="button"
                  onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                  tabIndex={-1}
                  className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
                  aria-label={showConfirmPassword ? 'Hide password' : 'Show password'}
                >
                  {showConfirmPassword ? <EyeOffIcon className="w-4 h-4" /> : <EyeIcon className="w-4 h-4" />}
                </button>
              </div>
              {validationErrors.confirmPassword && (
                <p className="text-xs text-red-600 mt-1.5 font-medium">{validationErrors.confirmPassword}</p>
              )}
            </div>

            {/* Submit Button */}
            <div className="pt-2">
              <button
                type="submit"
                disabled={isSubmitting || !!successMessage}
                className={`w-full py-3 px-4 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 hover:shadow-lg hover:shadow-emerald-700/25 transition-all duration-150 flex items-center justify-center gap-2 cursor-pointer ${
                  isSubmitting || !!successMessage ? 'opacity-70 cursor-not-allowed' : ''
                }`}
              >
                {isSubmitting ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Updating Credentials...
                  </>
                ) : (
                  'Update Credentials'
                )}
              </button>
            </div>
          </form>

          {/* Back to Sign In Option */}
          <div className="mt-6 pt-5 border-t border-gray-100 text-center">
            <button
              type="button"
              onClick={() => navigate('/login')}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-700 hover:text-emerald-800 hover:underline transition-colors cursor-pointer"
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
              Back to Sign In
            </button>
          </div>
        </div>

        {/* Footer info */}
        <div className="text-center mt-6">
          <p className="text-xs text-gray-400">
            EquiFlow — Hospitality Workforce Operations &copy; {new Date().getFullYear()} • All rights reserved
          </p>
        </div>
      </div>
    </div>
  );
}
