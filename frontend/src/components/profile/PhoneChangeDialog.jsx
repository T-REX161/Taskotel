import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { PHONE_MAX_LENGTH, PHONE_MESSAGES, phoneErrorMessage, savePhone, validatePhone } from '../../utils/phoneNumber';

function Spinner() {
  return (
    <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

const INPUT_CLASS = 'w-full h-11 px-4 rounded-xl border text-sm font-medium text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500 disabled:opacity-60';

/**
 * Editing the phone number. The number is saved as typed (country code allowed) and is NOT verified: there is no SMS step, and
 * nothing here says otherwise. The server saves it for the signed-in account only; on success the updated profile is handed to the
 * page, which shows it at once. Everything typed stays in this component (never in browser storage), a second click while the
 * request runs is ignored, and the dialog cannot be dismissed while it runs.
 */
export default function PhoneChangeDialog({ isOpen, currentPhone, onClose, onChanged }) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [fieldError, setFieldError] = useState('');
  const saving = useRef(false);
  const fieldRef = useRef(null);

  // A fresh dialog every time it opens
  useEffect(() => {
    if (!isOpen) return;
    setValue(''); setBusy(false); setError(''); setFieldError('');
    fieldRef.current?.focus();
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !saving.current) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const save = async (event) => {
    event.preventDefault();
    if (saving.current) return; // no duplicate submissions
    const problem = validatePhone(value, currentPhone);
    if (problem) { setFieldError(problem); return; }
    saving.current = true;
    setBusy(true); setError(''); setFieldError('');
    try {
      const updated = await savePhone(value, currentPhone); // only a saved number gets this far
      saving.current = false;
      setBusy(false);
      onChanged(updated);
    } catch (err) {
      setError(phoneErrorMessage(err, PHONE_MESSAGES.saveFailed));
      saving.current = false;
      setBusy(false);
    }
  };

  const dismiss = () => { if (!saving.current) onClose(); };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn" onClick={dismiss}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="phone-change-title"
        className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5"
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <h3 id="phone-change-title" className="text-lg font-bold text-gray-900">{currentPhone ? 'Change phone number' : 'Add phone number'}</h3>
          <p className="text-sm text-gray-500 mt-1">Include your country code, for example +91. Your number is saved as you enter it; it is not verified.</p>
        </div>

        <form onSubmit={save} noValidate className="space-y-4">
          <div>
            <label htmlFor="current-phone" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Current phone number</label>
            <input id="current-phone" type="text" value={currentPhone || 'Not added'} readOnly disabled className={`${INPUT_CLASS} border-gray-200 bg-gray-50`} />
          </div>
          <div>
            <label htmlFor="new-phone" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">New phone number</label>
            <input
              id="new-phone"
              ref={fieldRef}
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              maxLength={PHONE_MAX_LENGTH}
              value={value}
              disabled={busy}
              onChange={(event) => { setValue(event.target.value); if (fieldError) setFieldError(''); if (error) setError(''); }}
              aria-invalid={fieldError ? 'true' : 'false'}
              aria-describedby={fieldError ? 'new-phone-error' : undefined}
              placeholder="+91 98765 43210"
              className={`${INPUT_CLASS} ${fieldError ? 'border-red-400' : 'border-gray-300'}`}
            />
            {fieldError && <p id="new-phone-error" className="text-xs text-red-600 mt-1.5 font-medium">{fieldError}</p>}
          </div>
          {error && <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</p>}
          <div className="flex items-center justify-end gap-3 pt-1">
            <button type="button" onClick={dismiss} disabled={busy} className="px-4 py-2 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-80 disabled:cursor-wait">
              {busy ? (<><Spinner />Saving…</>) : 'Save Changes'}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}
