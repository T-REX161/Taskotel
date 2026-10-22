import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { EyeIcon, EyeOffIcon, LockIcon, ProfileIcon } from '../components/icons/Icons';
import TaskotelLogo from '../components/brand/TaskotelLogo';
import './LoginPage.css';
import { getLoginOutcome } from './loginResult';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialUsername = location.state?.username || '';
  const verifiedMessage = location.state?.verifiedMessage || '';

  const [username, setUsername] = useState(initialUsername);
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const [loginSuccess, setLoginSuccess] = useState(false);
  const [loginError, setLoginError] = useState(null); // { kind: 'invalid' | 'server' | 'network', message }
  // Incremented per attempt and used as the result alerts' key, so each new result mounts (and animates) once
  const [attemptId, setAttemptId] = useState(0);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setIsLoading(true);
    // A new attempt clears the previous result
    setLoginError(null);
    setLoginSuccess(false);
    setAttemptId((id) => id + 1);

    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ username, password }),
      });

      const data = response.ok ? await response.json().catch(() => null) : null;
      const outcome = getLoginOutcome({ ok: response.ok, status: response.status, data });

      // If email verification is required for unverified Staff:
      // Do NOT store JWT, navigate to /email-verification with verification state
      if (outcome.kind === 'verification') {
        navigate('/email-verification', {
          state: {
            identifier: data.username,
            username: data.username,
            email: data.email,
            role: data.role,
            message: data.message,
          },
        });
        return;
      }

      if (outcome.kind === 'success') {
        // Successful verified login: store JWT and navigate to dashboard
        localStorage.setItem('token', data.token);
        localStorage.setItem('username', data.username);
        localStorage.setItem('role', data.role);

        setLoginSuccess(true);
        setTimeout(() => {
          navigate('/');
        }, 700);
        return;
      }

      // Invalid credentials (401) or a server-side failure
      setLoginError(outcome);
    } catch (err) {
      setLoginError(getLoginOutcome({ networkError: true }));
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f2f6f3] text-gray-900 flex flex-col justify-center items-center p-4 sm:p-6 lg:p-8 relative selection:bg-emerald-100 selection:text-emerald-900">
      {/* Decorative ambient background glows */}
      <div className="absolute top-1/4 -left-20 w-72 h-72 bg-emerald-200/30 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-teal-200/25 rounded-full blur-3xl pointer-events-none" />

      {/* Main Centered Login Card */}
      <div className="w-full max-w-md relative z-10">
        <div className="bg-white rounded-2xl sm:rounded-3xl border border-gray-200/80 shadow-xl shadow-emerald-950/5 p-7 sm:p-10">

          {/* Taskotel Branding / Logo */}
          <div className="flex flex-col items-center text-center mb-8">
            <TaskotelLogo className="w-16 h-16 mb-3 transform transition-transform hover:scale-105 duration-200" />

            <div className="flex items-center gap-1.5">
              <span className="text-xl font-extrabold tracking-tight text-[#04302a]">
                Task<span className="text-[#0f9874]">otel</span>
              </span>
              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md bg-emerald-50 text-emerald-700 border border-emerald-200/60">
                Staff Portal
              </span>
            </div>

            <h1 className="text-2xl font-bold text-gray-900 tracking-tight mt-5">
              Welcome Back
            </h1>
            <p className="text-sm text-gray-500 mt-1.5">
              Sign in to your Taskotel account
            </p>
          </div>

          {/* Verification Success Banner (when returning from email verification) */}
          {verifiedMessage && !loginError && !loginSuccess && (
            <div className="mb-6 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn">
              <svg className="w-4 h-4 text-emerald-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
              <span>{verifiedMessage}</span>
            </div>
          )}

          {/* Success Banner (when simulated sign in triggers) */}
          {loginSuccess && (
            <div
              key={`success-${attemptId}`}
              role="status"
              className="mb-6 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn"
            >
              <span className="login-result-icon w-5 h-5 shrink-0 rounded-full bg-emerald-500 flex items-center justify-center" aria-hidden="true">
                <svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                  <path className="login-result-icon__stroke" pathLength="1" strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              </span>
              <span>Signed in successfully! Redirecting to dashboard...</span>
            </div>
          )}

          {/* Error Banner: animated red ✕ only for invalid credentials; server/network errors are text only */}
          {loginError && !loginSuccess && (
            <div
              key={`error-${attemptId}`}
              role="alert"
              className="mb-6 p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center gap-2.5 animate-fadeIn"
            >
              {loginError.kind === 'invalid' && (
                <span className="login-result-icon w-5 h-5 shrink-0 rounded-full bg-red-500 flex items-center justify-center" aria-hidden="true">
                  <svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                    <path className="login-result-icon__stroke login-result-icon__stroke--first" pathLength="1" strokeLinecap="round" strokeLinejoin="round" d="M6 6l12 12" />
                    <path className="login-result-icon__stroke login-result-icon__stroke--second" pathLength="1" strokeLinecap="round" strokeLinejoin="round" d="M18 6L6 18" />
                  </svg>
                </span>
              )}
              <span>{loginError.message}</span>
            </div>
          )}

          {/* Login Form */}
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Username Field */}
            <div>
              <label
                htmlFor="username"
                className="block text-xs font-bold uppercase tracking-wider text-gray-700 mb-1.5"
              >
                Username
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                  <ProfileIcon className="w-4 h-4" />
                </div>
                <input
                  id="username"
                  name="username"
                  type="text"
                  required
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="Enter your username"
                  className="w-full pl-10 pr-4 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-sm text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                />
              </div>
            </div>

            {/* Password Field with Show/Hide button */}
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label
                  htmlFor="password"
                  className="block text-xs font-bold uppercase tracking-wider text-gray-700"
                >
                  Password
                </label>
              </div>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-gray-400">
                  <LockIcon className="w-4 h-4" />
                </div>
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  className="w-full pl-10 pr-11 py-2.5 bg-gray-50/70 hover:bg-gray-50 focus:bg-white text-sm text-gray-900 placeholder-gray-400 rounded-xl border border-gray-200/90 focus:border-emerald-500 focus:ring-4 focus:ring-emerald-500/10 transition-all outline-none"
                />
                {/* Show/Hide password toggle */}
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-gray-400 hover:text-emerald-700 transition-colors focus:outline-none"
                  aria-label={showPassword ? "Hide password" : "Show password"}
                >
                  {showPassword ? (
                    <EyeOffIcon className="w-4 h-4" />
                  ) : (
                    <EyeIcon className="w-4 h-4" />
                  )}
                </button>
              </div>
            </div>

            {/* Remember Me Checkbox & Forgot Password */}
            <div className="flex items-center justify-between pt-1">
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={rememberMe}
                  onChange={(e) => setRememberMe(e.target.checked)}
                  className="w-4 h-4 rounded text-emerald-600 border-gray-300 focus:ring-emerald-500/20 focus:ring-offset-0 cursor-pointer accent-emerald-600"
                />
                <span className="text-xs font-medium text-gray-600">
                  Remember me
                </span>
              </label>

              <button
                type="button"
                onClick={() => navigate('/forgot-password')}
                className="text-xs font-semibold text-emerald-700 hover:text-emerald-800 hover:underline transition-colors"
              >
                Forgot password?
              </button>
            </div>

            {/* Green Sign In Button */}
            <div className="pt-2">
              <button
                type="submit"
                disabled={isLoading || loginSuccess}
                className={`w-full py-3 px-4 rounded-xl text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-md shadow-emerald-700/20 hover:shadow-lg hover:shadow-emerald-700/25 transition-all duration-150 flex items-center justify-center gap-2 cursor-pointer ${isLoading ? 'opacity-85 cursor-wait' : ''
                  }`}
              >
                {isLoading ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Signing in...
                  </>
                ) : (
                  'Sign In'
                )}
              </button>
            </div>
          </form>

        </div>

        {/* Footer info */}
        <div className="text-center mt-6">
          <p className="text-xs text-gray-400">
            Taskotel — Hotel Operations, Simplified &copy; {new Date().getFullYear()} • All rights reserved
          </p>
        </div>
      </div>
    </div>
  );
}
