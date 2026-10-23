import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { LockIcon, EyeIcon, EyeOffIcon } from '../components/icons/Icons';
import TaskotelLogo from '../components/brand/TaskotelLogo';
import { requestPasswordReset, confirmPasswordReset } from '../services/api';

export default function ForgotPasswordPage() {
  const navigate = useNavigate();
  
  // Steps: 1 = Request Reset, 2 = Confirm Code & New Password
  const [step, setStep] = useState(1);
  const [email, setEmail] = useState('');
  
  const [code, setCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  const handleRequestReset = async (e) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    setSuccessMsg('');

    try {
      await requestPasswordReset(email);
      setSuccessMsg('If this email is registered, a password reset code has been sent.');
      setStep(2);
    } catch (err) {
      setError(err.message || 'Failed to request password reset.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleConfirmReset = async (e) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setIsLoading(true);
    setError('');
    setSuccessMsg('');

    try {
      await confirmPasswordReset({ email, code, newPassword, confirmPassword });
      setSuccessMsg('Password has been successfully reset! Redirecting to login...');
      setTimeout(() => {
        navigate('/login');
      }, 2000);
    } catch (err) {
      setError(err.message || 'Failed to reset password.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f2f6f3] dark:bg-surface-page text-gray-900 flex flex-col justify-center items-center p-4 sm:p-6 lg:p-8 relative selection:bg-emerald-100 selection:text-emerald-900">
      <div className="absolute top-1/4 -left-20 w-72 h-72 bg-emerald-200/30 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-teal-200/25 rounded-full blur-3xl pointer-events-none" />

      <div className="w-full max-w-md relative z-10">
        <div className="bg-white rounded-2xl sm:rounded-3xl border border-gray-200/80 shadow-xl shadow-emerald-950/5 p-7 sm:p-10">
          
          <div className="flex flex-col items-center text-center mb-8">
            <TaskotelLogo className="w-16 h-16 mb-3 transform transition-transform hover:scale-105 duration-200" />

            <div className="flex items-center gap-1.5">
              <span className="text-xl font-extrabold tracking-tight text-[#04302a] dark:text-neutral-100">
                Task<span className="text-[#0f9874]">otel</span>
              </span>
            </div>

            <h1 className="text-2xl font-bold text-gray-900 tracking-tight mt-5">
              {step === 1 ? 'Forgot Password' : 'Reset Password'}
            </h1>
            <p className="text-sm text-gray-500 mt-1.5">
              {step === 1 ? 'Enter your email to receive a reset code' : 'Enter the 6-digit code and your new password'}
            </p>
          </div>

          {error && (
            <div className="mb-6 p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center gap-2.5 animate-fadeIn">
              <span>{error}</span>
            </div>
          )}

          {successMsg && (
            <div className="mb-6 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn">
              <svg className="w-4 h-4 text-emerald-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
              <span>{successMsg}</span>
            </div>
          )}

          {step === 1 ? (
            <form onSubmit={handleRequestReset} className="space-y-5">
              <div>
                <label htmlFor="email" className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5">
                  Email Address
                </label>
                <input
                  id="email"
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="name@hotel.com"
                  className="w-full px-4 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-sm text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                />
              </div>

              <div className="pt-2">
                <button
                  type="submit"
                  disabled={isLoading}
                  className={`w-full py-3 px-4 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 hover:shadow-lg transition-all duration-150 flex items-center justify-center gap-2 cursor-pointer ${isLoading ? 'opacity-85 cursor-wait' : ''}`}
                >
                  {isLoading ? 'Sending...' : 'Send Reset Code'}
                </button>
              </div>
            </form>
          ) : (
            <form onSubmit={handleConfirmReset} className="space-y-5">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5">
                  Email Address
                </label>
                <input
                  type="email"
                  value={email}
                  disabled
                  className="w-full px-4 py-2.5 bg-gray-100 text-sm text-gray-500 rounded-xl border border-gray-200 cursor-not-allowed"
                />
              </div>

              <div>
                <label htmlFor="code" className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5">
                  6-Digit Reset Code
                </label>
                <input
                  id="code"
                  type="text"
                  required
                  maxLength="6"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="123456"
                  className="w-full px-4 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-center tracking-widest text-lg font-mono text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                />
              </div>

              <div>
                <label htmlFor="newPassword" className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5">
                  New Password
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                    <LockIcon className="w-4 h-4" />
                  </div>
                  <input
                    id="newPassword"
                    type={showPassword ? 'text' : 'password'}
                    required
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="Create a strong password"
                    className="w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-sm text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-emerald-700 transition-colors focus:outline-none"
                  >
                    {showPassword ? <EyeOffIcon className="w-4 h-4" /> : <EyeIcon className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              <div>
                <label htmlFor="confirmPassword" className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5">
                  Confirm Password
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                    <LockIcon className="w-4 h-4" />
                  </div>
                  <input
                    id="confirmPassword"
                    type={showConfirmPassword ? 'text' : 'password'}
                    required
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    placeholder="Confirm your new password"
                    className="w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-sm text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-emerald-700 transition-colors focus:outline-none"
                  >
                    {showConfirmPassword ? <EyeOffIcon className="w-4 h-4" /> : <EyeIcon className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              <div className="pt-2">
                <button
                  type="submit"
                  disabled={isLoading}
                  className={`w-full py-3 px-4 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 hover:shadow-lg transition-all duration-150 flex items-center justify-center gap-2 cursor-pointer ${isLoading ? 'opacity-85 cursor-wait' : ''}`}
                >
                  {isLoading ? 'Resetting...' : 'Reset Password'}
                </button>
              </div>
            </form>
          )}

          <div className="mt-6 text-center">
            <button
              onClick={() => navigate('/login')}
              className="text-sm font-medium text-emerald-600 hover:text-emerald-700 hover:underline"
            >
              Back to Login
            </button>
          </div>

        </div>
      </div>
    </div>
  );
}
