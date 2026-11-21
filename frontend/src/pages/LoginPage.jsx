import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { EyeIcon, EyeOffIcon, LockIcon, ProfileIcon } from '../components/icons/Icons';
import TaskotelLogo from '../components/brand/TaskotelLogo';
import './LoginPage.css';
import { getLoginOutcome } from './loginResult';

// Illustration only (decorative preview card on the photo panel): sample departments and loads, not live data
const SAMPLE_LOADS = [
  ['Housekeeping', 72],
  ['Front Desk', 48],
  ['Maintenance', 86],
  ['Food & Beverage', 35],
];

// Soft mint specks that rise slowly over the photo panel (decorative; see LoginPage.css, off with reduced motion)
const PARTICLES = [
  { left: '7%', size: 5, dur: 19, delay: 0 },
  { left: '16%', size: 8, dur: 26, delay: 6 },
  { left: '27%', size: 4, dur: 21, delay: 12 },
  { left: '38%', size: 6, dur: 24, delay: 3 },
  { left: '49%', size: 9, dur: 30, delay: 9 },
  { left: '58%', size: 4, dur: 20, delay: 15 },
  { left: '68%', size: 7, dur: 27, delay: 1 },
  { left: '77%', size: 5, dur: 23, delay: 8 },
  { left: '86%', size: 8, dur: 29, delay: 13 },
  { left: '94%', size: 4, dur: 22, delay: 5 },
];

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialUsername = location.state?.username || '';
  const verifiedMessage = location.state?.verifiedMessage || '';

  const [username, setUsername] = useState(initialUsername);
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [loginSuccess, setLoginSuccess] = useState(false);
  const [loginError, setLoginError] = useState(null); // { kind: 'invalid' | 'rateLimited' | 'server' | 'network', message }
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

      // The body is read for a success, a 429 and a 401 (whose message is shown only for the expired-temporary-password code); other failures use fixed messages
      const data = response.ok || response.status === 429 || response.status === 401 || response.status >= 500 ? await response.json().catch(() => null) : null;
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

  // A banner covers the subtitle's spot above the username field, so nothing is pushed down
  const hasBanner = loginSuccess || Boolean(loginError) || Boolean(verifiedMessage);

  return (
    <div className="login-page relative min-h-screen text-gray-900 flex flex-col overflow-x-hidden selection:bg-emerald-100 selection:text-emerald-900">
      {/* Full-screen split: form on the left, green-tinted hotel photo with a short pitch on the right */}
      <main className="login-frame relative flex-1 w-full min-h-screen overflow-hidden">
        {/* One full-screen animated scene behind everything: the hotel photo (slow zoom), a green wash, drifting colour, a light sheen and rising gold specks (all decorative) */}
        <div className="login-scene absolute inset-0" aria-hidden="true">
          <img
            src="/images/login-background.jpg"
            alt=""
            className="login-photo login-kenburns absolute inset-0 h-full w-full object-cover object-center"
          />
          <div className="login-bg-tint absolute inset-0" />
          <div className="login-orbs absolute inset-0 overflow-hidden pointer-events-none">
            <span className="login-orb login-orb--one" />
            <span className="login-orb login-orb--two" />
          </div>
          <div className="login-sheen absolute inset-0" />
          <div className="absolute inset-0 overflow-hidden">
            {PARTICLES.map((particle) => (
              <span
                key={particle.left}
                className="login-particle"
                style={{ left: particle.left, width: particle.size, height: particle.size, animationDuration: `${particle.dur}s`, animationDelay: `${particle.delay}s` }}
              />
            ))}
          </div>
        </div>

        <div className="relative z-10 mx-auto flex min-h-screen w-full max-w-[1400px] flex-col items-center justify-center gap-12 px-5 py-14 sm:px-10 md:flex-row lg:gap-24 lg:px-16">
          {/* The form: a frosted card floating on the scene */}
          <section className="login-card relative w-full max-w-[440px] shrink-0 rounded-[28px] p-7 sm:p-10">
        <div className="login-stagger relative">

          {/* Taskotel Branding / Logo */}
          <div className="mb-[19px]">
            <div className="flex items-center gap-4">
              <span className="login-logo-tile inline-flex shrink-0 rounded-2xl p-2"><TaskotelLogo className="w-12 h-12 shrink-0" theme="dark" /></span>
              <div className="flex flex-col">
                <span className="login-wordmark text-[30px] font-bold tracking-tight text-white leading-[34px]">
                  Task<span className="text-emerald-300">otel</span>
                </span>
                <span className="mt-1 text-[10.5px] font-semibold uppercase tracking-[0.18em] text-white/65">
                  Hotel Operations, Simplified
                </span>
              </div>
            </div>

            <h1 className="text-[27px] sm:text-[29px] leading-9 font-bold text-white tracking-tight mt-7">
              Welcome Back
            </h1>
            <p className={`text-[15.5px] text-white/75 mt-0.5 ${hasBanner ? "invisible" : ""}`}>
              Sign in to your Taskotel account
            </p>
          </div>

          {/* Reserved space for the result banners: the card keeps the same size with or without one */}
          <div className="login-alert-slot">
            {/* Verification Success Banner (when returning from email verification) */}
            {verifiedMessage && !loginError && !loginSuccess && (
              <div className="p-2 rounded-xl bg-green-50 border border-green-200 text-green-800 text-sm flex items-center gap-2.5 animate-fade-in">
                <svg className="w-4 h-4 text-green-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
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
                className="p-2 rounded-xl bg-green-50 border border-green-200 text-green-800 text-sm flex items-center gap-2.5 animate-fade-in"
              >
                <span className="login-result-icon w-5 h-5 shrink-0 rounded-full bg-green-500 flex items-center justify-center" aria-hidden="true">
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
                className="p-2 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center gap-2.5 animate-fade-in"
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
          </div>

          {/* Login Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            {/* Username or Email Field (sent as `username`; the server accepts either) */}
            <div>
              <label htmlFor="username" className="mb-1.5 block text-[13px] font-semibold text-white/90">
                Username or Email
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none text-white/55">
                  <ProfileIcon className="w-[18px] h-[18px]" />
                </div>
                <input
                  id="username"
                  name="username"
                  type="text"
                  required
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="Enter your username or email"
                  autoComplete="username"
                  className="w-full h-12 pl-11 pr-4 login-input text-sm text-white placeholder-white/45 rounded-xl focus:ring-4 focus:ring-emerald-400/20 transition-[color,background-color,border-color,box-shadow,opacity] outline-none"
                />
              </div>
            </div>

            {/* Password Field with Show/Hide button */}
            <div>
              <div className="mb-1.5 flex items-center justify-between">
                <label htmlFor="password" className="block text-[13px] font-semibold text-white/90">
                  Password
                </label>
                <button
                  type="button"
                  onClick={() => navigate('/forgot-password')}
                  className="text-[13px] font-medium text-emerald-300 hover:text-emerald-200 hover:underline transition-colors"
                >
                  Forgot password?
                </button>
              </div>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none text-white/55">
                  <LockIcon className="w-[18px] h-[18px]" />
                </div>
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  autoComplete="current-password"
                  className="w-full h-12 pl-11 pr-11 login-input text-sm text-white placeholder-white/45 rounded-xl focus:ring-4 focus:ring-emerald-400/20 transition-[color,background-color,border-color,box-shadow,opacity] outline-none"
                />
                {/* Show/Hide password toggle */}
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute inset-y-0 right-0 pr-4 flex items-center text-white/55 hover:text-white transition-colors focus:outline-none"
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

            {/* Green Sign In Button */}
            <div className="pt-1.5">
              <button
                type="submit"
                disabled={isLoading || loginSuccess}
                className={`w-full h-12 px-4 login-cta rounded-xl text-[15px] font-semibold hover:shadow-lg hover:shadow-emerald-600/35 transition-[color,background-color,border-color,box-shadow,opacity] duration-base flex items-center justify-center gap-2 cursor-pointer ${isLoading ? 'opacity-85 cursor-wait' : ''
                  }`}
              >
                {isLoading ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-emerald-900" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Signing in...
                  </>
                ) : (
                  <>
                    Sign In
                    <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
                  </>
                )}
              </button>
            </div>
          </form>

          <p className="text-center text-[12.5px] text-white/60 mt-6">
            Manage staff, tasks and workloads effortlessly.
          </p>
        </div>
          </section>

          {/* The pitch: plain text and a preview card straight on the scene (hidden on small screens and from assistive tech) */}
          <div className="login-pitch hidden max-w-[520px] flex-1 flex-col text-white md:flex" aria-hidden="true">
            <div className="mb-8 inline-flex w-fit items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-white/90">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
              Hotel operations platform
            </div>
            <div className="h-1 w-12 rounded-full bg-emerald-500 mb-5" />
            <p className="text-[32px] lg:text-[44px] font-bold leading-[1.1] tracking-tight">
              Balanced workloads.<br />Smarter operations.
            </p>
            <ul className="mt-7 space-y-3.5">
              <li className="flex items-start gap-3 text-sm leading-snug text-white/85">
                <span className="mt-px flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-500/20 ring-1 ring-emerald-400/60 text-emerald-300">
                  <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                </span>
                Live workload visibility across every department
              </li>
              <li className="flex items-start gap-3 text-sm leading-snug text-white/85">
                <span className="mt-px flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-500/20 ring-1 ring-emerald-400/60 text-emerald-300">
                  <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                </span>
                Fair task assignment based on shift capacity
              </li>
              <li className="flex items-start gap-3 text-sm leading-snug text-white/85">
                <span className="mt-px flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-500/20 ring-1 ring-emerald-400/60 text-emerald-300">
                  <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                </span>
                Role-based access for admins, managers and staff
              </li>
            </ul>
            <div className="login-glass mt-9 w-full max-w-[340px] rounded-2xl p-5 text-white">
              <div className="mb-4 flex items-center justify-between">
                <span className="text-[13px] font-semibold tracking-wide">Workload overview</span>
                <span className="inline-flex items-center gap-1.5 text-[11px] font-medium text-emerald-300">
                  <span className="login-pulse h-1.5 w-1.5 rounded-full bg-emerald-400" />
                  Today
                </span>
              </div>
              <ul className="space-y-3.5">
                {SAMPLE_LOADS.map(([name, percent], index) => (
                  <li key={name}>
                    <div className="mb-1.5 flex items-center justify-between text-xs">
                      <span className="text-white/85">{name}</span>
                      <span className="font-semibold text-white">{percent}%</span>
                    </div>
                    <div className="login-track h-1.5 w-full overflow-hidden rounded-full">
                      <div
                        className="login-bar h-full rounded-full bg-gradient-to-r from-emerald-500 to-emerald-300"
                        style={{ width: `${percent}%`, animationDelay: `${0.6 + index * 0.15}s` }}
                      />
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>

        {/* Footer info */}
        <p className="text-center text-xs text-white/60 absolute bottom-5 inset-x-0 z-20 px-4">
          Taskotel — Hotel Operations, Simplified &copy; {new Date().getFullYear()} • All rights reserved
        </p>
      </main>

    </div>
  );
}
