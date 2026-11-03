import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { EyeIcon, EyeOffIcon, LockIcon } from '../icons/Icons';
import PasswordRequirements from '../common/PasswordRequirements';
import { evaluatePasswordRules } from '../../utils/passwordPolicy';
import { PASSWORD_MESSAGES, passwordErrorMessage, submitPasswordChange } from '../../utils/passwordChange';

const EMPTY = { currentPassword: '', newPassword: '', confirmPassword: '' };

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

/** One password input with a lock icon, a show/hide button and its own error line. */
function PasswordField({ id, label, value, error, onChange, disabled, autoComplete, inputRef }) {
  const [shown, setShown] = useState(false); // each field shows or hides on its own
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-semibold text-gray-800 mb-1.5">{label}</label>
      <div className="relative">
        <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
          <LockIcon className="w-4 h-4" />
        </div>
        <input
          id={id}
          ref={inputRef}
          name={id}
          type={shown ? 'text' : 'password'}
          autoComplete={autoComplete}
          disabled={disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          placeholder="••••••••"
          className={`w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-gray-900 placeholder-gray-400 rounded-xl border text-sm transition-all outline-none disabled:opacity-60 ${
            error ? 'border-red-300 focus:border-red-500 focus:ring-4 focus:ring-red-500/10' : 'border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10'
          }`}
        />
        <button
          type="button"
          onClick={() => setShown(!shown)}
          className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
          aria-label={shown ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
        >
          {shown ? <EyeOffIcon className="w-4 h-4" /> : <EyeIcon className="w-4 h-4" />}
        </button>
      </div>
      {error && <p id={`${id}-error`} className="text-xs text-red-600 mt-1.5 font-medium">{error}</p>}
    </div>
  );
}

/**
 * Change Password, opened from the Account Activity section of the profile header (it replaces the Security Settings card).
 * It uses the same validation, password rules, request and error messages as before (utils/passwordChange, utils/passwordPolicy,
 * the existing password-change endpoint). The three passwords live only in this component's state, never in browser storage, and are cleared
 * whenever the dialog closes. It closes only after the server confirms the change, or on Cancel / Escape / a click outside; none of
 * those work while a change is being sent, and a second click is ignored. onChanged(message) tells the page, which shows the
 * success message and refreshes the Last Password Change time.
 */
export default function ChangePasswordDialog({ isOpen, onClose, onChanged }) {
  const [values, setValues] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [status, setStatus] = useState({ phase: 'idle', message: '' }); // idle | saving | error
  const sending = useRef(false);
  const firstField = useRef(null);

  // Everything typed is dropped as soon as the dialog closes, and every opening starts clean
  useEffect(() => {
    setValues(EMPTY);
    setErrors({});
    setStatus({ phase: 'idle', message: '' });
    sending.current = false;
    if (isOpen) firstField.current?.focus();
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !sending.current) onClose();
    };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const rules = evaluatePasswordRules(values.newPassword);
  const saving = status.phase === 'saving';

  const edit = (field) => (value) => {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((e) => (e[field] ? { ...e, [field]: '' } : e));
    if (status.phase === 'error') setStatus({ phase: 'idle', message: '' });
  };

  const submit = async (event) => {
    event.preventDefault();
    if (sending.current) return; // no duplicate submissions
    sending.current = true;
    setStatus({ phase: 'saving', message: '' });
    try {
      await submitPasswordChange(values);
      sending.current = false;
      onChanged(PASSWORD_MESSAGES.saved); // the page closes the dialog; the fields are cleared by the effect above
    } catch (error) {
      sending.current = false;
      if (error?.errors) {
        setErrors(error.errors);
        setStatus({ phase: 'idle', message: '' });
      } else {
        setErrors({});
        setStatus({ phase: 'error', message: passwordErrorMessage(error) });
      }
    }
  };

  const dismiss = () => {
    if (!sending.current) onClose();
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn" onClick={dismiss}>
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="change-password-title"
        aria-busy={saving}
        noValidate
        onSubmit={submit}
        onClick={(event) => event.stopPropagation()}
        className="flex flex-col w-full max-w-2xl max-h-[90vh] bg-white rounded-2xl shadow-xl border border-gray-100 overflow-hidden"
      >
        <div className="shrink-0 px-5 sm:px-6 py-5 border-b border-gray-200">
          <h3 id="change-password-title" className="text-lg font-bold text-gray-900 leading-tight">Change Password</h3>
          <p className="text-sm text-gray-500 mt-1.5">Choose a strong new password to protect your Taskotel account.</p>
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-5 sm:px-6 py-5 grid gap-5 md:grid-cols-2 md:gap-8 items-start">
          <div className="space-y-4">
            <PasswordField id="currentPassword" label="Current Password" autoComplete="current-password" value={values.currentPassword}
              error={errors.currentPassword} disabled={saving} onChange={edit('currentPassword')} inputRef={firstField} />
            <PasswordField id="newPassword" label="New Password" autoComplete="new-password" value={values.newPassword}
              error={errors.newPassword} disabled={saving} onChange={edit('newPassword')} />
            <PasswordField id="confirmPassword" label="Confirm New Password" autoComplete="new-password" value={values.confirmPassword}
              error={errors.confirmPassword} disabled={saving} onChange={edit('confirmPassword')} />
          </div>

          <div className="space-y-4 md:pt-7">
            <PasswordRequirements rules={rules} />
            {status.phase === 'error' && (
              <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                {status.message}
              </p>
            )}
          </div>
        </div>

        <div className="shrink-0 flex items-center justify-end gap-3 px-5 sm:px-6 py-4 border-t border-gray-200 bg-gray-50">
          <button
            type="button"
            onClick={dismiss}
            disabled={saving}
            className="h-10 px-4 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={saving}
            className="h-10 px-5 inline-flex items-center justify-center gap-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-semibold rounded-xl cursor-pointer disabled:opacity-70 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
          >
            {saving ? (<><Spinner />Changing password…</>) : 'Change Password'}
          </button>
        </div>
      </form>
    </div>,
    document.body
  );
}
