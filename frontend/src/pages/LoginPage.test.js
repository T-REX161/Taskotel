import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getLoginOutcome, LOGIN_MESSAGES } from './loginResult.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('LoginPage result indicators', () => {
  const pageSource = fs.readFileSync(path.join(__dirname, 'LoginPage.jsx'), 'utf8');
  const css = fs.readFileSync(path.join(__dirname, 'LoginPage.css'), 'utf8');
  const successBanner = pageSource.match(/\{loginSuccess && \([\s\S]*?\n {12}\)\}/)?.[0] || '';
  const errorBanner = pageSource.match(/\{loginError && !loginSuccess && \([\s\S]*?\n {12}\)\}/)?.[0] || '';
  const handleSubmit = pageSource.match(/const handleSubmit = async[\s\S]*?\n {2}\};/)?.[0] || '';

  describe('getLoginOutcome (login result classification)', () => {
    it('successful login: an OK response with a token', () => {
      assert.deepStrictEqual(getLoginOutcome({ ok: true, status: 200, data: { token: 't', username: 'u', role: 'ADMIN' } }), { kind: 'success', message: '' });
    });

    it('email verification required is not treated as success', () => {
      assert.strictEqual(getLoginOutcome({ ok: true, status: 200, data: { emailVerificationRequired: true, token: 't' } }).kind, 'verification');
    });

    it('invalid credentials: only a 401, with a message that does not say which field was wrong', () => {
      const outcome = getLoginOutcome({ ok: false, status: 401 });
      assert.deepStrictEqual(outcome, { kind: 'invalid', message: 'Invalid username or password. Please try again.' });
      assert.strictEqual(/\bthe (username|password)\b|username is|password is/i.test(outcome.message), false);
    });

    it('server failures are not labelled as invalid credentials', () => {
      for (const status of [400, 403, 404, 500, 502, 503]) {
        assert.deepStrictEqual(getLoginOutcome({ ok: false, status }), { kind: 'server', message: LOGIN_MESSAGES.server }, `status ${status}`);
      }
      assert.strictEqual(getLoginOutcome({ ok: true, status: 200, data: null }).kind, 'server', 'OK without a readable body is not a success');
      assert.strictEqual(getLoginOutcome({ ok: true, status: 200, data: { username: 'u' } }).kind, 'server', 'OK without a token is not a success');
    });

    it('network failures get their own message', () => {
      assert.deepStrictEqual(getLoginOutcome({ networkError: true }), { kind: 'network', message: LOGIN_MESSAGES.network });
      assert.notStrictEqual(LOGIN_MESSAGES.network, LOGIN_MESSAGES.invalid);
    });
  });

  describe('success indicator (green ✓)', () => {
    it('shows a small white checkmark centered in a 20px green circle', () => {
      assert.ok(successBanner, 'Success banner must exist');
      assert.ok(
        successBanner.includes('<span className="login-result-icon w-5 h-5 shrink-0 rounded-full bg-emerald-500 flex items-center justify-center" aria-hidden="true">'),
        'Icon must be a 20px emerald circle that centers its content'
      );
      assert.ok(
        successBanner.includes('<svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">'),
        'Checkmark must be a 14px white stroke icon'
      );
      assert.ok(successBanner.includes('<path className="login-result-icon__stroke" pathLength="1"'), 'Tick must be drawn with the stroke animation');
      assert.ok(successBanner.includes('d="M5 13l4 4L19 7"'), 'Checkmark must reuse the existing check path');
    });

    it('keeps the existing message, alert styling, and announces politely', () => {
      assert.ok(successBanner.includes('Signed in successfully! Redirecting to dashboard...'), 'Message must be unchanged');
      assert.ok(successBanner.includes('role="status"'), 'Success must be announced as a status');
      assert.ok(
        successBanner.includes('className="p-2 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center gap-2.5 animate-fadeIn"'),
        'Alert container styling must be unchanged (icon vertically centered via items-center)'
      );
    });
  });

  describe('invalid-credentials indicator (red ✕)', () => {
    it('shows a small white ✕ centered in a red circle the same size as the success circle', () => {
      assert.ok(errorBanner, 'Error banner must exist');
      assert.ok(
        errorBanner.includes('<span className="login-result-icon w-5 h-5 shrink-0 rounded-full bg-red-500 flex items-center justify-center" aria-hidden="true">'),
        'Error icon must be a 20px red circle that centers its content'
      );
      assert.ok(
        errorBanner.includes('<svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">'),
        '✕ must be a 14px white stroke icon with the same stroke as the ✓'
      );
      assert.ok(errorBanner.includes('login-result-icon__stroke login-result-icon__stroke--first" pathLength="1" strokeLinecap="round" strokeLinejoin="round" d="M6 6l12 12"'), 'First ✕ stroke');
      assert.ok(errorBanner.includes('login-result-icon__stroke login-result-icon__stroke--second" pathLength="1" strokeLinecap="round" strokeLinejoin="round" d="M18 6L6 18"'), 'Second ✕ stroke');
    });

    it('shows the ✕ only for invalid credentials; server and network errors are text only', () => {
      assert.ok(errorBanner.includes("{loginError.kind === 'invalid' && ("), 'The ✕ must be limited to invalid credentials');
      assert.ok(errorBanner.includes('<span>{loginError.message}</span>'), 'The classified message is shown');
    });

    it('uses red-tinted alert styling matching the success alert and is announced assertively', () => {
      assert.ok(
        errorBanner.includes('className="p-2 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center gap-2.5 animate-fadeIn"'),
        'Error alert must mirror the success alert layout in red'
      );
      assert.ok(errorBanner.includes('role="alert"'), 'Errors must be announced as alerts');
    });
  });

  describe('state handling', () => {
    it('a new attempt clears the previous result and gets a new key, so each result animates once', () => {
      assert.ok(
        /setIsLoading\(true\);\s*\/\/[^\n]*\n\s*setLoginError\(null\);\s*setLoginSuccess\(false\);\s*setAttemptId\(\(id\) => id \+ 1\);/.test(handleSubmit),
        'Submitting must clear the old indicator before the request'
      );
      assert.ok(successBanner.includes('key={`success-${attemptId}`}'), 'Success alert is keyed per attempt');
      assert.ok(errorBanner.includes('key={`error-${attemptId}`}'), 'Error alert is keyed per attempt');
    });

    it('never shows both indicators at once', () => {
      assert.ok(pageSource.includes('{loginError && !loginSuccess && ('), 'Error is hidden whenever success is shown');
      const successBranch = handleSubmit.match(/if \(outcome\.kind === 'success'\) \{[\s\S]*?return;\s*\}/)?.[0] || '';
      assert.ok(successBranch && !successBranch.includes('setLoginError('), 'Success never sets an error');
      assert.strictEqual((handleSubmit.match(/setLoginSuccess\(true\)/g) || []).length, 1, 'Success is set in exactly one place');
    });

    it('success is set only after the backend confirms and the token is stored', () => {
      assert.ok(
        /if \(outcome\.kind === 'success'\) \{\s*\/\/[^\n]*\n\s*localStorage\.setItem\('token', data\.token\);[\s\S]*?setLoginSuccess\(true\);/.test(handleSubmit),
        'Success state follows storing the token for a confirmed login'
      );
      assert.ok(
        /if \(outcome\.kind === 'verification'\) \{[\s\S]*?return;\s*\}[\s\S]*if \(outcome\.kind === 'success'\)/.test(handleSubmit),
        'Email-verification redirects return before success handling'
      );
    });

    it('invalid credentials and server errors use the classified outcome; network failures use the network message', () => {
      assert.ok(handleSubmit.includes('getLoginOutcome({ ok: response.ok, status: response.status, data })'), 'Responses are classified');
      assert.ok(/\/\/ Invalid credentials \(401\) or a server-side failure\s*setLoginError\(outcome\);/.test(handleSubmit), 'Failure outcomes are shown');
      assert.ok(/catch \(err\) \{\s*setLoginError\(getLoginOutcome\(\{ networkError: true \}\)\);/.test(handleSubmit), 'Request failures are network errors');
      assert.ok(handleSubmit.includes('await response.json().catch(() => null)'), 'An unreadable OK body is a server error, not a network error');
    });

    it('keeps the redirect, loading state, password toggle and remember-me behaviour', () => {
      assert.ok(/setTimeout\(\(\) => \{\s*navigate\('\/'\);\s*\}, 700\);/.test(handleSubmit), 'Redirect to the dashboard must be unchanged');
      assert.ok(/finally \{\s*setIsLoading\(false\);/.test(handleSubmit), 'Loading state always ends');
      assert.ok(pageSource.includes('disabled={isLoading || loginSuccess}'), 'Sign In stays disabled while loading or after success');
      assert.ok(pageSource.includes("type={showPassword ? 'text' : 'password'}"), 'Password visibility toggle unchanged');
      assert.ok(pageSource.includes('checked={rememberMe}'), 'Remember me unchanged');
      assert.ok(pageSource.includes("{verifiedMessage && !loginError && !loginSuccess && ("), 'Verification banner hides while a result is shown');
    });
  });

  describe('split-screen layout', () => {
    it('shows the login card beside a local hotel reception photo that covers its half', () => {
      assert.ok(pageSource.includes('src="/images/login-background.jpg"'), 'Photo is a local asset');
      assert.ok(fs.existsSync(path.join(__dirname, '..', '..', 'public', 'images', 'login-background.jpg')), 'Photo file exists in public/images');
      assert.ok(/<img\s+src="\/images\/login-background\.jpg"\s+alt=""\s+className="login-photo h-full w-full object-cover /.test(pageSource), 'Decorative photo fills its section without stretching');
      assert.ok(/\.login-photo \{[^}]*mask-image: linear-gradient\(to right, transparent 0%, #000 14%\);/.test(css), 'Photo fades into the green frame so there is no seam');
      assert.ok(pageSource.includes('className="absolute inset-y-0 right-0 hidden md:block md:w-[50%]" aria-hidden="true"'), 'Photo is hidden on small screens and from assistive tech');
      assert.ok(pageSource.includes('md:w-[54%] lg:w-[46.2%]'), 'Card matches the reference (about 46% of the container), a little wider on tablet');
      assert.ok(pageSource.includes('max-w-[1024px]') && pageSource.includes('md:min-h-[519px]'), 'Container keeps the reference proportions: 1024 x 519');
      assert.ok(pageSource.includes('rounded-[20px] overflow-hidden'), 'Rounded container clips the photo to its corners');
    });

    it('keeps accessible labels for the placeholder-styled fields and the footer below the container', () => {
      assert.ok(pageSource.includes('<label htmlFor="username" className="sr-only">'), 'Username keeps a label');
      assert.ok(pageSource.includes('<label htmlFor="password" className="sr-only">'), 'Password keeps a label');
      assert.ok(/<\/main>\s*\{\/\* Footer info \*\/\}\s*<p className="text-center text-xs/.test(pageSource), 'Footer sits below the container');
      assert.ok(pageSource.includes("fetch('/api/auth/login'") && pageSource.includes('JSON.stringify({ username, password })'), 'Login request unchanged');
    });
  });

  describe('animation', () => {
    const rule = (selector) => css.match(new RegExp(`(^|\\n)${selector.replace(/[.]/g, '\\.')} \\{([^}]*)\\}`))?.[2] || '';
    const ms = (text) => [...text.matchAll(/(\d+)ms/g)].map((m) => Number(m[1]));

    it('loads the login-only stylesheet and animates the icons only, not the alerts', () => {
      assert.ok(pageSource.includes("import './LoginPage.css';"), 'LoginPage must import its own stylesheet');
      assert.strictEqual((pageSource.match(/className="login-result-icon /g) || []).length, 2, 'Exactly the two circles get the entrance animation');
      assert.strictEqual(/className="p-2[^"]*login-result/.test(pageSource), false, 'Alert containers are not animated');
    });

    it('scales and fades each circle in from its center without bouncing, spinning or looping', () => {
      assert.ok(rule('.login-result-icon').includes('animation: login-result-circle-in'), 'Circle uses the entrance keyframes');
      assert.ok(/@keyframes login-result-circle-in \{\s*from \{\s*opacity: 0;\s*transform: scale\(0\.6\);\s*\}\s*to \{\s*opacity: 1;\s*transform: scale\(1\);/.test(css), 'Entrance is scale 0.6 to 1 with fade');
      assert.strictEqual(/scale\(1\.[1-9]/.test(css) || /rotate/.test(css) || /infinite/.test(css), false, 'No bounce, spin or loop');
    });

    it('draws the strokes with stroke-dashoffset and ends fully drawn', () => {
      const stroke = rule('.login-result-icon__stroke');
      assert.ok(stroke.includes('stroke-dasharray: 1;') && stroke.includes('stroke-dashoffset: 0;'), 'At rest the strokes are fully drawn');
      assert.ok(/0% \{\s*stroke-dashoffset: 1;/.test(css) && /100% \{\s*stroke-dashoffset: 0;/.test(css), 'Draw goes from hidden to complete');
    });

    it('both icons finish within 400–600ms and the ✕ strokes are staggered', () => {
      const [circle] = ms(rule('.login-result-icon'));
      const [tickDuration, tickDelay] = ms(rule('.login-result-icon__stroke'));
      const [firstDuration, firstDelay] = ms(rule('.login-result-icon__stroke--first'));
      const [secondDuration, secondDelay] = ms(rule('.login-result-icon__stroke--second'));
      const tickTotal = Math.max(circle, tickDelay + tickDuration);
      const crossTotal = Math.max(circle, firstDelay + firstDuration, secondDelay + secondDuration);
      for (const total of [tickTotal, crossTotal]) assert.ok(total >= 400 && total <= 600, `Total must be 400–600ms (got ${total}ms)`);
      assert.strictEqual(tickTotal, crossTotal, 'Both icons take the same time');
      assert.ok(secondDelay > firstDelay, 'Second ✕ stroke starts after the first');
      assert.ok(rule('.login-result-icon').trim().endsWith('both;') && rule('.login-result-icon__stroke').trim().endsWith('both;'), 'Plays once and holds the final frame');
      assert.strictEqual((pageSource.match(/setTimeout\(/g) || []).length, 1, 'No new timers (only the existing redirect)');
    });

    it('shows completed icons without animation when reduced motion is preferred', () => {
      assert.ok(
        /@media \(prefers-reduced-motion: reduce\) \{\s*\.login-result-icon,\s*\.login-result-icon__stroke \{\s*animation: none;/.test(css),
        'Reduced motion disables the circle and stroke animations (strokes are drawn at rest)'
      );
    });
  });
});
