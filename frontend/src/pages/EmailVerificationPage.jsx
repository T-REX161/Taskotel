import React, { useState, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import EquiFlowLogo from '../components/brand/EquiFlowLogo';

/**
 * Mask an email address for privacy (e.g., jo***@hotel.com).
 */
function maskEmail(email) {
  if (!email || !email.includes('@')) return email || '';
  const [local, domain] = email.split('@');
  if (local.length <= 2) {
    return `${local[0] || ''}***@${domain}`;
  }
  return `${local.slice(0, 2)}***@${domain}`;
}

export default function EmailVerificationPage() {
  const navigate = useNavigate();
  const location = useLocation();

  // Retrieve verification context passed from LoginPage
  const { identifier, email, username } = location.state || {};
  const effectiveIdentifier = identifier || username;

  const [otp, setOtp] = useState(['', '', '', '', '', '']);
  const inputRefs = useRef([]);
  const [validationError, setValidationError] = useState('');
  const [error, setError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // If user refreshes or visits directly without active verification session state
  if (!effectiveIdentifier && !email) {
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
              Verification Session Unavailable
            </h1>
            <p className="text-sm text-gray-500 mt-2 mb-6">
              No active verification session was found. Please sign in with your credentials to initiate email verification.
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

  const handleOtpChange = (e, index) => {
    const rawVal = e.target.value;

    // Detect if non-numeric characters were entered
    if (/[^0-9]/.test(rawVal)) {
      setValidationError('Verification code can only contain numeric digits (0-9).');
      return;
    }

    setValidationError('');
    setError('');

    const digits = rawVal.replace(/\D/g, '');

    // In case multiple digits are entered at once (e.g., browser autofill)
    if (digits.length > 1) {
      const newOtp = [...otp];
      for (let i = 0; i < digits.length && index + i < 6; i++) {
        newOtp[index + i] = digits[i];
      }
      setOtp(newOtp);
      const nextIndex = Math.min(index + digits.length, 5);
      inputRefs.current[nextIndex]?.focus();
      return;
    }

    const newOtp = [...otp];
    newOtp[index] = digits;
    setOtp(newOtp);

    // Auto-advance focus to the next input box
    if (digits && index < 5) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handleKeyDown = (e, index) => {
    if (e.key === 'Backspace') {
      if (otp[index]) {
        // Clear current box if it has a digit
        const newOtp = [...otp];
        newOtp[index] = '';
        setOtp(newOtp);
        setValidationError('');
        setError('');
      } else if (index > 0) {
        // Current box is empty, navigate to previous box and clear it
        const newOtp = [...otp];
        newOtp[index - 1] = '';
        setOtp(newOtp);
        setValidationError('');
        setError('');
        inputRefs.current[index - 1]?.focus();
      }
      e.preventDefault();
    } else if (e.key === 'ArrowLeft' && index > 0) {
      e.preventDefault();
      inputRefs.current[index - 1]?.focus();
    } else if (e.key === 'ArrowRight' && index < 5) {
      e.preventDefault();
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handlePaste = (e) => {
    e.preventDefault();
    const pastedText = e.clipboardData.getData('text');
    const digits = pastedText.replace(/\D/g, '').slice(0, 6);

    if (/[^0-9]/.test(pastedText.trim())) {
      setValidationError('Verification code can only contain numeric digits (0-9).');
    } else {
      setValidationError('');
    }
    setError('');

    if (!digits) return;

    const newOtp = [...otp];
    for (let i = 0; i < 6; i++) {
      newOtp[i] = digits[i] || '';
    }
    setOtp(newOtp);

    // Move focus to next unfilled box or the last box
    const nextIndex = Math.min(digits.length, 5);
    inputRefs.current[nextIndex]?.focus();
  };

  const handleVerify = async (e) => {
    e.preventDefault();
    setValidationError('');
    setError('');

    const code = otp.join('');

    if (code.length !== 6) {
      setValidationError('Verification code must be exactly 6 digits.');
      return;
    }

    setIsSubmitting(true);

    try {
      const response = await fetch('/api/auth/email-verification/verify', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          identifier: effectiveIdentifier,
          code,
        }),
      });

      const data = await response.json().catch(() => null);

      if (response.ok && data?.success) {
        if (data.setupToken) {
          setSuccessMessage('Email verified successfully! Proceeding to account setup...');
          setTimeout(() => {
            navigate('/set-credentials', {
              state: {
                setupToken: data.setupToken,
                username: effectiveIdentifier,
                email: email,
              },
            });
          }, 800);
        } else {
          setSuccessMessage('Email verified successfully! Returning to login...');
          setTimeout(() => {
            navigate('/login', {
              state: {
                verifiedMessage: 'Email verified successfully. Please sign in to continue.',
                username: effectiveIdentifier,
              },
            });
          }, 1200);
        }
      } else {
        const errorMsg = data?.message || 'Verification failed. Please check the code and try again.';
        setError(errorMsg);
      }
    } catch (err) {
      setError('Network error. Please check your connection and try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f2f6f3] text-gray-900 flex flex-col justify-center items-center p-4 sm:p-6 lg:p-8 relative selection:bg-emerald-100 selection:text-emerald-900">
      {/* Decorative ambient background glows */}
      <div className="absolute top-1/4 -left-20 w-72 h-72 bg-emerald-200/30 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-teal-200/25 rounded-full blur-3xl pointer-events-none" />

      {/* Main Centered Verification Card */}
      <div className="w-full max-w-md relative z-10">
        <div className="bg-white rounded-2xl sm:rounded-3xl border border-gray-200/80 shadow-xl shadow-emerald-950/5 p-7 sm:p-10">

          {/* EquiFlow Branding */}
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
              Verify Your Email
            </h1>
            <p className="text-sm text-gray-500 mt-1.5 max-w-sm">
              Please enter the 6-digit verification code sent to your registered email address to complete your account setup.
            </p>
          </div>

          {/* Masked Email Badge */}
          {email && (
            <div className="mb-6 p-3.5 bg-emerald-50/70 border border-emerald-200/70 rounded-xl flex items-center gap-3 text-left">
              <div className="w-9 h-9 rounded-lg bg-emerald-100 flex items-center justify-center text-emerald-700 shrink-0">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                </svg>
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-[11px] font-medium uppercase tracking-wider text-gray-500">
                  Verification Code Sent To
                </p>
                <p className="text-sm font-semibold text-gray-900 truncate" title={email}>
                  {maskEmail(email)}
                </p>
              </div>
            </div>
          )}

          {/* Success Banner */}
          {successMessage && (
            <div className="mb-5 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-ping" />
              <span>{successMessage}</span>
            </div>
          )}

          {/* Backend Error Banner */}
          {error && (
            <div className="mb-5 p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-2.5 animate-fadeIn">
              <svg className="w-4 h-4 text-red-500 mt-0.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <span>{error}</span>
            </div>
          )}

          {/* Verification Code Form */}
          <form onSubmit={handleVerify} className="space-y-5">
            <div>
              <label
                className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-3 text-center"
              >
                Enter 6-Digit Code
              </label>

              <div className="flex justify-center items-center gap-2 sm:gap-2.5">
                {otp.map((digit, index) => (
                  <input
                    key={index}
                    ref={(el) => (inputRefs.current[index] = el)}
                    id={`otp-${index}`}
                    name={`otp-${index}`}
                    type="text"
                    inputMode="numeric"
                    autoComplete={index === 0 ? 'one-time-code' : 'off'}
                    pattern="[0-9]*"
                    maxLength={1}
                    autoFocus={index === 0}
                    disabled={isSubmitting || !!successMessage}
                    value={digit}
                    onChange={(e) => handleOtpChange(e, index)}
                    onKeyDown={(e) => handleKeyDown(e, index)}
                    onPaste={handlePaste}
                    onFocus={(e) => e.target.select()}
                    className={`w-10 h-12 sm:w-[48px] sm:h-[52px] text-center text-xl sm:text-2xl font-bold font-mono rounded-lg bg-white border transition-all duration-150 outline-none select-none ${
                      validationError
                        ? 'border-red-300 focus:border-red-500 focus:ring-2 focus:ring-red-500/20 text-red-900'
                        : digit
                        ? 'border-emerald-500/80 text-gray-900 focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/20'
                        : 'border-gray-200 hover:border-gray-300 text-gray-900 focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/20'
                    } ${isSubmitting || !!successMessage ? 'opacity-60 cursor-not-allowed' : ''}`}
                    aria-label={`Digit ${index + 1} of 6`}
                  />
                ))}
              </div>

              {/* Validation Message */}
              {validationError ? (
                <p className="text-xs text-red-600 mt-2.5 text-center font-medium animate-fadeIn">
                  {validationError}
                </p>
              ) : (
                <p className="text-xs text-gray-400 mt-2.5 text-center">
                  Numeric digits only (expires in 10 minutes)
                </p>
              )}
            </div>

            {/* Verify Button */}
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
                    Verifying Code...
                  </>
                ) : (
                  'Verify Email'
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
