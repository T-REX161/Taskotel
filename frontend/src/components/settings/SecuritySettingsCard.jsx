import React, { useRef, useState } from 'react';
import { EyeIcon, EyeOffIcon, LockIcon } from '../icons/Icons';
import PasswordRequirements from '../common/PasswordRequirements';
import { evaluatePasswordRules } from '../../utils/passwordPolicy';
import { PASSWORD_MESSAGES, passwordErrorMessage, submitPasswordChange } from '../../utils/passwordChange';

function ShieldIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

/** One password input with a lock icon, a show/hide button and its own error line. */
function PasswordField({ id, label, value, error, onChange, disabled, autoComplete }) {
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
 * Settings > Security Settings > Change Password. The three passwords live only in this component's state (never in browser
 * storage) and are cleared after a successful change. The server checks everything again; "changed" is shown only after it confirms.
 * A second click while a change is being sent is ignored.
 */
export default function SecuritySettingsCard() {
  const [values, setValues] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [errors, setErrors] = useState({});
  const [status, setStatus] = useState({ phase: 'idle', message: '' }); // idle | saving | saved | error
  const sending = useRef(false);

  const rules = evaluatePasswordRules(values.newPassword);
  const saving = status.phase === 'saving';

  const edit = (field) => (value) => {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((e) => (e[field] ? { ...e, [field]: '' } : e));
    if (status.phase === 'saved' || status.phase === 'error') setStatus({ phase: 'idle', message: '' });
  };

  const submit = async (event) => {
    event.preventDefault();
    if (sending.current) return; // no duplicate submissions
    sending.current = true;
    setStatus({ phase: 'saving', message: '' });
    try {
      await submitPasswordChange(values);
      setValues({ currentPassword: '', newPassword: '', confirmPassword: '' });
      setErrors({});
      setStatus({ phase: 'saved', message: PASSWORD_MESSAGES.saved });
    } catch (error) {
      if (error?.errors) {
        setErrors(error.errors);
        setStatus({ phase: 'idle', message: '' });
      } else {
        setErrors({});
        setStatus({ phase: 'error', message: passwordErrorMessage(error) });
      }
    } finally {
      sending.current = false;
    }
  };

  return (
    <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-labelledby="security-settings-title">
      <div className="flex items-center gap-4 px-6 sm:px-8 py-6 border-b border-gray-100">
        <ShieldIcon className="w-7 h-7 text-gray-500 shrink-0" />
        <div className="min-w-0">
          <h2 id="security-settings-title" className="text-lg font-bold text-gray-900">Security Settings</h2>
          <p className="text-sm text-gray-500 mt-0.5">Manage your password and protect your Taskotel account.</p>
        </div>
      </div>

      <form onSubmit={submit} noValidate className="px-6 sm:px-8 py-6" aria-busy={saving} aria-label="Change password">
        <h3 className="text-sm font-bold text-gray-900 mb-4">Change Password</h3>

        <div className="grid gap-6 lg:grid-cols-2 lg:gap-10">
          <div className="space-y-4">
            <PasswordField id="currentPassword" label="Current Password" autoComplete="current-password" value={values.currentPassword}
              error={errors.currentPassword} disabled={saving} onChange={edit('currentPassword')} />
            <PasswordField id="newPassword" label="New Password" autoComplete="new-password" value={values.newPassword}
              error={errors.newPassword} disabled={saving} onChange={edit('newPassword')} />
            <PasswordField id="confirmPassword" label="Confirm New Password" autoComplete="new-password" value={values.confirmPassword}
              error={errors.confirmPassword} disabled={saving} onChange={edit('confirmPassword')} />
          </div>

          <div className="lg:pt-7">
            <PasswordRequirements rules={rules} />
          </div>
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-4">
          <button
            type="submit"
            disabled={saving}
            className={`inline-flex items-center justify-center gap-2 py-2.5 px-5 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 transition-all cursor-pointer ${saving ? 'opacity-70 cursor-not-allowed' : ''}`}
          >
            {saving ? (<><Spinner />Changing password…</>) : 'Change Password'}
          </button>

          {status.phase === 'saved' && (
            <p role="status" aria-live="polite" className="text-sm font-medium text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-1.5">
              {status.message}
            </p>
          )}
          {status.phase === 'error' && (
            <p role="alert" className="text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5">
              {status.message}
            </p>
          )}
        </div>
      </form>
    </section>
  );
}
