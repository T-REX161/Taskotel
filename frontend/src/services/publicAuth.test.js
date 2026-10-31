import { describe, it, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  setupCredentials,
  requestPasswordReset,
  confirmPasswordReset,
  publicAuthErrorMessage,
  usableBackendMessage,
  RATE_LIMIT_MESSAGE,
  SERVER_ERROR_MESSAGE,
  NETWORK_ERROR_MESSAGE,
} from './api.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

/** Replaces fetch with one that answers once; `body` is the JSON value, a string to send malformed JSON, or undefined for no body. */
const respond = (status, body) => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => {
        if (typeof body === 'string') throw new SyntaxError('Unexpected token');
        if (body === undefined) throw new SyntaxError('Unexpected end of JSON input');
        return body;
      },
    };
  };
  return calls;
};
const failNetwork = () => { globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); }; };

const calls = {
  request: () => requestPasswordReset('person@hotel.test'),
  confirm: () => confirmPasswordReset({ email: 'person@hotel.test', code: '123456', newPassword: 'Secret@123', confirmPassword: 'Secret@123' }),
  setup: () => setupCredentials({ setupToken: 'tok-123', newUsername: 'newuser', newPassword: 'Secret@123', confirmPassword: 'Secret@123' }),
};
const rejection = async (fn) => { try { await fn(); } catch (e) { return e; } assert.fail('expected the call to fail'); };

describe('HTTP 429 from the password-reset and credential-setup endpoints', () => {
  for (const [name, fn] of Object.entries(calls)) {
    it(`${name}: shows the backend's generic message and keeps the status`, async () => {
      const backend = 'Too many requests. Please wait a few minutes and try again.';
      respond(429, { message: backend });
      const error = await rejection(fn);
      assert.equal(error.message, backend);
      assert.equal(error.status, 429);
    });

    it(`${name}: falls back to the fixed message when the body is missing, malformed or unusable`, async () => {
      for (const body of [undefined, 'not json', null, {}, { message: '' }, { message: '   ' }, { message: 42 }, { message: { a: 1 } }, { message: ['x'] }, { message: 'x'.repeat(201) }, [], 'text']) {
        respond(429, body);
        const error = await rejection(fn);
        assert.equal(error.message, RATE_LIMIT_MESSAGE, JSON.stringify(body));
        assert.equal(error.status, 429);
      }
    });
  }
});

describe('other failures are unchanged or safer', () => {
  it('5xx never shows the response body, for all three calls', async () => {
    for (const fn of Object.values(calls)) {
      respond(500, { message: 'java.lang.NullPointerException at com.hotel.workload.Secret.line(42)', trace: 'stack' });
      const error = await rejection(fn);
      assert.equal(error.message, SERVER_ERROR_MESSAGE);
      assert.equal(error.status, 500);
      respond(503, undefined);
      assert.equal((await rejection(fn)).message, SERVER_ERROR_MESSAGE);
    }
  });

  it('reset request and confirm keep showing the backend validation message for a 400, with their existing fallbacks', async () => {
    respond(400, { message: 'Invalid code or email.' });
    assert.equal((await rejection(calls.confirm)).message, 'Invalid code or email.');
    respond(400, { message: 'Password must contain at least one special character' });
    assert.equal((await rejection(calls.confirm)).message, 'Password must contain at least one special character');
    respond(400, undefined);
    assert.equal((await rejection(calls.confirm)).message, 'Failed to reset password. Please check your inputs and try again.');
    respond(404, '<html>');
    assert.equal((await rejection(calls.request)).message, 'Failed to request password reset. Please try again later.');
  });

  it('credential setup keeps its friendly mapping for validation errors', async () => {
    const cases = [
      [{ message: 'Invalid or expired setup token' }, 'Your setup session is invalid or has expired. Please verify your email again.'],
      [{ message: 'Username already exists' }, 'This username is already taken. Please choose a different username.'],
      [{ message: 'Password must contain at least one digit' }, 'Password does not meet the security requirements.'],
      [{ message: 'Credentials setup is not required for this user' }, 'Account setup has already been completed for this user. Please sign in.'],
    ];
    for (const [body, expected] of cases) {
      respond(400, body);
      assert.equal((await rejection(calls.setup)).message, expected);
    }
    respond(400, undefined);
    assert.equal((await rejection(calls.setup)).message, 'Invalid request. Please verify your inputs and try again.');
    respond(403, { message: 42 });
    assert.equal((await rejection(calls.setup)).message, 'Failed to update credentials. Please try again.');
  });

  it('a network failure gets a fixed message, not the browser text', async () => {
    for (const fn of Object.values(calls)) {
      failNetwork();
      const error = await rejection(fn);
      assert.equal(error.message, NETWORK_ERROR_MESSAGE);
      assert.equal(error.status, 0);
    }
  });

  it('no error message ever contains the code, token, password or email that was sent', async () => {
    for (const status of [400, 401, 404, 429, 500]) {
      for (const fn of Object.values(calls)) {
        respond(status, { message: 'Too many requests. Please wait a few minutes and try again.' });
        const message = (await rejection(fn)).message;
        for (const secret of ['123456', 'tok-123', 'Secret@123', 'person@hotel.test']) assert.ok(!message.includes(secret), `${status}: ${secret}`);
      }
    }
  });
});

describe('successful requests', () => {
  it('request and confirm resolve with nothing, and send the same endpoints, methods and bodies as before', async () => {
    let seen = respond(200, undefined);
    assert.equal(await calls.request(), null);
    assert.equal(seen[0].url, '/api/auth/password-reset/request');
    assert.equal(seen[0].options.method, 'POST');
    assert.deepEqual(JSON.parse(seen[0].options.body), { email: 'person@hotel.test' });
    assert.equal(seen[0].options.headers.Authorization, undefined, 'no sign-in token is sent');

    seen = respond(200, undefined);
    assert.equal(await calls.confirm(), null);
    assert.equal(seen[0].url, '/api/auth/password-reset/confirm');
    assert.deepEqual(JSON.parse(seen[0].options.body), { email: 'person@hotel.test', code: '123456', newPassword: 'Secret@123', confirmPassword: 'Secret@123' });
  });

  it('setup returns the response and uses the same endpoint, method and body as before', async () => {
    const seen = respond(200, { token: 'jwt', username: 'newuser' });
    assert.deepEqual(await calls.setup(), { token: 'jwt', username: 'newuser' });
    assert.equal(seen[0].url, '/api/auth/credentials/setup');
    assert.equal(seen[0].options.method, 'PUT');
    assert.deepEqual(JSON.parse(seen[0].options.body), { setupToken: 'tok-123', newUsername: 'newuser', newPassword: 'Secret@123', confirmPassword: 'Secret@123' });
  });
});

describe('message helpers', () => {
  it('usableBackendMessage accepts only a short, non-empty string', () => {
    assert.equal(usableBackendMessage({ message: ' Slow down. ' }), 'Slow down.');
    for (const data of [null, undefined, 'x', 3, [], {}, { message: null }, { message: '' }, { message: 'x'.repeat(201) }]) assert.equal(usableBackendMessage(data), '');
  });
  it('publicAuthErrorMessage picks by status', () => {
    assert.equal(publicAuthErrorMessage(429, null, 'fb'), RATE_LIMIT_MESSAGE);
    assert.equal(publicAuthErrorMessage(500, { message: 'boom' }, 'fb'), SERVER_ERROR_MESSAGE);
    assert.equal(publicAuthErrorMessage(400, { message: 'Bad' }, 'fb'), 'Bad');
    assert.equal(publicAuthErrorMessage(400, null, 'fb'), 'fb');
  });
});

describe('the pages that call them', () => {
  const forgot = fs.readFileSync(path.join(here, '../pages/ForgotPasswordPage.jsx'), 'utf8').replace(/\r\n/g, '\n');
  const setup = fs.readFileSync(path.join(here, '../pages/SetCredentialsPage.jsx'), 'utf8').replace(/\r\n/g, '\n');

  it('only the forgot-password and set-credentials pages call these functions, and they show the error message as text', () => {
    assert.ok(forgot.includes('await requestPasswordReset(email);') && forgot.includes('await confirmPasswordReset({ email, code, newPassword, confirmPassword });'));
    assert.ok(forgot.includes("setError(err.message || 'Failed to request password reset.');") && forgot.includes("setError(err.message || 'Failed to reset password.');"));
    assert.ok(setup.includes('await setupCredentials({') && setup.includes("setServerError(err.message || 'Failed to update credentials. Please try again.');"));
  });

  it('keep their successful flows, local validation and navigation', () => {
    assert.ok(forgot.includes("setStep(2);") && forgot.includes("navigate('/login');") && forgot.includes('Passwords do not match.'));
    assert.ok(setup.includes('if (!validateForm()) {') && setup.includes("navigate('/login', {"));
  });
});

describe('hardening: unrecognised or unsafe messages from credential setup', () => {
  const SETUP_FALLBACK = 'Failed to update credentials. Please try again.';
  const BAD_REQUEST_FALLBACK = 'Invalid request. Please verify your inputs and try again.';

  it('shows an unrecognised 4xx message only when it is a short, plain string', async () => {
    for (const status of [400, 403, 404, 422]) {
      respond(status, { message: 'This account is locked for review.' });
      assert.equal((await rejection(calls.setup)).message, 'This account is locked for review.', `status ${status}`);
    }
    respond(400, { message: '  Please try again later.  ' });
    assert.equal((await rejection(calls.setup)).message, 'Please try again later.', 'trimmed');
    respond(400, { message: 'x'.repeat(200) });
    assert.equal((await rejection(calls.setup)).message, 'x'.repeat(200), 'the limit itself is allowed');
  });

  it('falls back for an over-long unrecognised message, 400 and other 4xx each with their own fallback', async () => {
    respond(400, { message: 'x'.repeat(201) });
    assert.equal((await rejection(calls.setup)).message, BAD_REQUEST_FALLBACK);
    respond(409, { message: 'y'.repeat(5000) });
    assert.equal((await rejection(calls.setup)).message, SETUP_FALLBACK);
  });

  it('falls back for non-string, empty and malformed bodies', async () => {
    for (const body of [{ message: 42 }, { message: true }, { message: null }, { message: { text: 'a' } }, { message: ['a'] }, { message: '' }, { message: '   ' }, {}, [], null, 'plain text', undefined]) {
      respond(400, body);
      assert.equal((await rejection(calls.setup)).message, BAD_REQUEST_FALLBACK, JSON.stringify(body));
      respond(403, body);
      assert.equal((await rejection(calls.setup)).message, SETUP_FALLBACK, JSON.stringify(body));
    }
  });

  it('does not show a bare framework "error" field such as "Bad Request"', async () => {
    respond(400, { error: 'Bad Request', status: 400, path: '/api/auth/credentials/setup' });
    assert.equal((await rejection(calls.setup)).message, BAD_REQUEST_FALLBACK);
  });

  it('never shows a message that repeats the setup token, password or confirmation that was sent', async () => {
    for (const echoed of ['tok-123', 'Secret@123']) {
      respond(400, { message: `Rejected value ${echoed}` });
      const message = (await rejection(calls.setup)).message;
      assert.equal(message, BAD_REQUEST_FALLBACK);
      assert.ok(!message.includes(echoed));
    }
    respond(429, { message: 'Slow down tok-123' });
    assert.equal((await rejection(calls.setup)).message, RATE_LIMIT_MESSAGE);
  });

  it('never shows a reset message that repeats the code or password that was sent', async () => {
    respond(400, { message: 'The code 123456 is wrong' });
    assert.equal((await rejection(calls.confirm)).message, 'Failed to reset password. Please check your inputs and try again.');
    respond(400, { message: 'Bad password Secret@123' });
    assert.equal((await rejection(calls.confirm)).message, 'Failed to reset password. Please check your inputs and try again.');
  });

  it('keeps every friendly mapping, whatever the message length and whether it comes in "message" or "error"', async () => {
    const cases = [
      ['Invalid or expired setup token', 'Your setup session is invalid or has expired. Please verify your email again.'],
      ['Setup token is required', 'Your setup session is invalid or has expired. Please verify your email again.'],
      ['Username already exists', 'This username is already taken. Please choose a different username.'],
      ['Duplicate entry', 'This username is already taken. Please choose a different username.'],
      ['Password must be at least 8 characters', 'Password does not meet the security requirements.'],
      ['Password must contain an uppercase letter', 'Password does not meet the security requirements.'],
      ['Password needs a special character', 'Password does not meet the security requirements.'],
      ['Credentials setup is not required for this user', 'Account setup has already been completed for this user. Please sign in.'],
      ['Setup already completed', 'Account setup has already been completed for this user. Please sign in.'],
    ];
    for (const [text, expected] of cases) {
      respond(400, { message: text });
      assert.equal((await rejection(calls.setup)).message, expected, text);
      respond(400, { error: text });
      assert.equal((await rejection(calls.setup)).message, expected, `error field: ${text}`);
      respond(400, { message: `${text} ${'.'.repeat(500)}` });
      assert.equal((await rejection(calls.setup)).message, expected, `long: ${text}`);
    }
  });

  it('does not attach the raw response body to the error, and keeps the status', async () => {
    respond(400, { message: 'Username already exists', debug: 'internal detail', echoed: 'tok-123' });
    const error = await rejection(calls.setup);
    assert.equal(error.status, 400);
    assert.equal(error.data, undefined);
    assert.ok(!JSON.stringify(Object.values(error)).includes('internal detail'));
  });

  it('429, 5xx and network failures behave as before', async () => {
    respond(429, { message: 'Too many requests. Please wait a few minutes and try again.' });
    assert.equal((await rejection(calls.setup)).message, 'Too many requests. Please wait a few minutes and try again.');
    respond(429, undefined);
    assert.equal((await rejection(calls.setup)).message, RATE_LIMIT_MESSAGE);
    respond(500, { message: 'Username already exists' });
    assert.equal((await rejection(calls.setup)).message, SERVER_ERROR_MESSAGE, 'a 5xx is never mapped or echoed');
    failNetwork();
    assert.equal((await rejection(calls.setup)).message, NETWORK_ERROR_MESSAGE);
  });
});
