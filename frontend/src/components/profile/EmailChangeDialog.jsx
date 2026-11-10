import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  EMAIL_CHANGE_MESSAGES, emailChangeErrorMessage, formatCountdown, isCompleteCode, onlyDigits, secondsLeft,
  sendEmailChangeCode, startingStep, validateNewEmail, verifyEmailChangeCode,
} from '../../utils/emailChange';

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
 * Changing the email address in two steps: enter the new address and get a code at it, then enter the code. The real email changes
 * only on the server, after the code is verified; this dialog then hands the updated profile to the page, which shows it at once.
 * Everything typed stays in this component (never in browser storage), a second click while a request is running is ignored, and
 * the dialog cannot be dismissed while one is.
 *
 * With a `pending` change ({ maskedEmail, expiresAt }), for example one an administrator or manager started for this account, it opens
 * straight on the code step: no new address, no password, and nothing is requested. The code goes to the same confirmation as always.
 */
export default function EmailChangeDialog({ isOpen, currentEmail, onClose, onChanged, pending = null }) {
  const [step, setStep] = useState('enter'); // enter | verify
  const [newEmail, setNewEmail] = useState('');
  const [currentPassword, setCurrentPassword] = useState(''); // re-authentication; lives only in this component, never in browser storage
  const [passwordError, setPasswordError] = useState('');
  const [code, setCode] = useState('');
  const [info, setInfo] = useState(null); // { maskedEmail, expiresAt, resendAt }
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [fieldError, setFieldError] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const [continuing, setContinuing] = useState(false); // true while finishing an existing pending change (no resend, no client-side expiry block)
  const pendingRef = useRef(pending);
  pendingRef.current = pending;
  const sending = useRef(false);
  const firstFieldRef = useRef(null);
  const codeRef = useRef(null);

  // A fresh dialog every time it opens
  useEffect(() => {
    if (!isOpen) return;
    const start = startingStep(pendingRef.current, Date.now());
    setStep(start.step); setContinuing(start.continuing); setNewEmail(''); setCurrentPassword(''); setPasswordError(''); setCode(''); setInfo(start.info); setBusy(false); setError(''); setFieldError('');
    setNow(Date.now());
  }, [isOpen]);

  // The countdowns tick once a second, only while the code step is showing; the timer is stopped when it closes
  useEffect(() => {
    if (!isOpen || step !== 'verify') return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [isOpen, step]);

  useEffect(() => {
    if (!isOpen) return;
    (step === 'enter' ? firstFieldRef : codeRef).current?.focus();
  }, [isOpen, step]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !sending.current) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const expiresKnown = Boolean(info) && Number.isFinite(info.expiresAt);
  const expiresIn = expiresKnown ? secondsLeft(info.expiresAt, now) : 0;
  const resendIn = info ? secondsLeft(info.resendAt, now) : 0;

  const sendCode = async (event) => {
    event?.preventDefault();
    if (sending.current) return; // no duplicate submissions
    const problem = validateNewEmail(newEmail, currentEmail);
    if (problem) { setFieldError(problem); return; }
    if (!currentPassword) { setPasswordError(EMAIL_CHANGE_MESSAGES.passwordRequired); return; }
    sending.current = true;
    setBusy(true); setError(''); setFieldError(''); setPasswordError('');
    try {
      setInfo(await sendEmailChangeCode(newEmail, currentEmail, currentPassword));
      setContinuing(false); // a request of their own now replaces the pending one
      setCode('');
      setNow(Date.now());
      setStep('verify');
    } catch (err) {
      setError(emailChangeErrorMessage(err, EMAIL_CHANGE_MESSAGES.sendFailed));
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const verify = async (event) => {
    event.preventDefault();
    if (sending.current) return;
    if (!isCompleteCode(code)) { setFieldError(EMAIL_CHANGE_MESSAGES.codeRequired); return; }
    sending.current = true;
    setBusy(true); setError(''); setFieldError('');
    try {
      const updated = await verifyEmailChangeCode(code); // only a confirmed change gets this far
      sending.current = false;
      setBusy(false);
      onChanged(updated);
    } catch (err) {
      setError(emailChangeErrorMessage(err, EMAIL_CHANGE_MESSAGES.verifyFailed));
      setCode('');
      sending.current = false;
      setBusy(false);
      codeRef.current?.focus();
    }
  };

  const dismiss = () => { if (!sending.current) onClose(); };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn" onClick={dismiss}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="email-change-title"
        className="bg-white rounded-2xl max-w-md w-full p-6 shadow-xl border border-gray-100 max-h-[85vh] overflow-y-auto space-y-5"
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <h3 id="email-change-title" className="text-lg font-bold text-gray-900">Change email address</h3>
          <p className="text-sm text-gray-500 mt-1">
            {step === 'enter'
              ? 'We will send a 6-digit code to the new address to confirm it is yours. Your email only changes after you enter it.'
              : continuing
                ? 'An email change is waiting for you. Enter the 6-digit code sent to the new address to confirm it is yours.'
                : 'Enter the 6-digit code we sent to confirm your new email address.'}
          </p>
        </div>

        {step === 'enter' ? (
          <form onSubmit={sendCode} noValidate className="space-y-4">
            <div>
              <label htmlFor="current-email" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Current email</label>
              <input id="current-email" type="email" value={currentEmail || ''} readOnly disabled className={`${INPUT_CLASS} border-gray-200 bg-gray-50`} />
            </div>
            <div>
              <label htmlFor="new-email" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">New email</label>
              <input
                id="new-email"
                ref={firstFieldRef}
                type="email"
                inputMode="email"
                autoComplete="off"
                value={newEmail}
                disabled={busy}
                onChange={(event) => { setNewEmail(event.target.value); if (fieldError) setFieldError(''); if (error) setError(''); }}
                aria-invalid={fieldError ? 'true' : 'false'}
                aria-describedby={fieldError ? 'new-email-error' : undefined}
                placeholder="name@example.com"
                className={`${INPUT_CLASS} ${fieldError ? 'border-red-400' : 'border-gray-300'}`}
              />
              {fieldError && <p id="new-email-error" className="text-xs text-red-600 mt-1.5 font-medium">{fieldError}</p>}
            </div>
            <div>
              <label htmlFor="email-change-password" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Current password</label>
              <input
                id="email-change-password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                disabled={busy}
                onChange={(event) => { setCurrentPassword(event.target.value); if (passwordError) setPasswordError(''); if (error) setError(''); }}
                aria-invalid={passwordError ? 'true' : 'false'}
                aria-describedby={passwordError ? 'email-change-password-error' : undefined}
                className={`${INPUT_CLASS} ${passwordError ? 'border-red-400' : 'border-gray-300'}`}
              />
              {passwordError && <p id="email-change-password-error" className="text-xs text-red-600 mt-1.5 font-medium">{passwordError}</p>}
              <p className="text-xs text-gray-500 mt-1.5">For your security, confirm it is you before the address is changed.</p>
            </div>
            {error && <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</p>}
            <div className="flex items-center justify-end gap-3 pt-1">
              <button type="button" onClick={dismiss} disabled={busy} className="px-4 py-2 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50">
                Cancel
              </button>
              <button type="submit" disabled={busy} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-80 disabled:cursor-wait">
                {busy ? (<><Spinner />Sending…</>) : 'Send Verification Code'}
              </button>
            </div>
          </form>
        ) : (
          <form onSubmit={verify} noValidate className="space-y-4">
            <p className="text-sm text-gray-700">
              Code sent to <span className="font-semibold text-gray-900">{info?.maskedEmail}</span>
            </p>
            <div>
              <label htmlFor="email-change-code" className="block text-xs font-semibold uppercase tracking-wide text-gray-500 mb-1.5">Verification code</label>
              <input
                id="email-change-code"
                ref={codeRef}
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                value={code}
                disabled={busy}
                onChange={(event) => { setCode(onlyDigits(event.target.value)); if (fieldError) setFieldError(''); if (error) setError(''); }}
                aria-invalid={fieldError ? 'true' : 'false'}
                aria-describedby={fieldError ? 'email-change-code-error' : undefined}
                placeholder="123456"
                className={`${INPUT_CLASS} tracking-[0.4em] tabular-nums text-center ${fieldError ? 'border-red-400' : 'border-gray-300'}`}
              />
              {fieldError && <p id="email-change-code-error" className="text-xs text-red-600 mt-1.5 font-medium">{fieldError}</p>}
              <p className="text-xs text-gray-500 mt-1.5" aria-live="off">
                {expiresIn > 0
                  ? <>Code expires in <span className="font-semibold tabular-nums">{formatCountdown(expiresIn)}</span>.</>
                  : continuing
                    ? 'If the code is refused it may have expired. You can start a new change below.'
                    : 'This code has expired. Send a new one.'}
              </p>
            </div>
            {error && <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</p>}
            <div className="flex items-center justify-between gap-3 pt-1">
              {continuing ? (
                <span aria-hidden="true" />
              ) : (
                <button
                  type="button"
                  onClick={sendCode}
                  disabled={busy || resendIn > 0}
                  className="text-sm font-semibold text-emerald-700 hover:text-emerald-800 disabled:text-gray-400 disabled:cursor-not-allowed cursor-pointer"
                >
                  {resendIn > 0 ? `Resend code in ${resendIn}s` : 'Resend Code'}
                </button>
              )}
              <div className="flex items-center gap-3">
                <button type="button" onClick={dismiss} disabled={busy} className="px-4 py-2 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50">
                  Cancel
                </button>
                <button type="submit" disabled={busy || (!continuing && expiresIn === 0)} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-80 disabled:cursor-not-allowed">
                  {busy ? (<><Spinner />Verifying…</>) : 'Verify Email'}
                </button>
              </div>
            </div>
            <button
              type="button"
              onClick={() => { setStep('enter'); setContinuing(false); setError(''); setFieldError(''); }}
              disabled={busy}
              className="text-xs font-semibold text-gray-500 hover:text-gray-700 cursor-pointer disabled:opacity-50"
            >
              {continuing ? 'Start a different change instead' : 'Use a different email address'}
            </button>
          </form>
        )}
      </div>
    </div>,
    document.body
  );
}
