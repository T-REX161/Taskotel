import { afterEach, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  SYSTEM_SETTINGS_MESSAGES, buildRequest, fromServer, isDirty, loadSystemSettings, parseThreshold, saveSystemSettings, systemSettingsErrorMessage, toForm,
} from './systemSettings.js';
import { getSystemSettings, updateSystemSettings } from '../services/api.js';

const serverAnswer = (over = {}) => ({
  autoBalancingEnabled: true,
  capacityAlertEnabled: false,
  crossDepartmentAssistanceEnabled: true,
  capacityThresholdPercent: 70,
  updatedAt: '2026-11-11T12:00:00',
  updatedBy: 'admin',
  ...over,
});

describe('reading the server answer', () => {
  it('a complete answer becomes the saved values, with who saved it', () => {
    assert.deepEqual(fromServer(serverAnswer()), {
      autoBalancingEnabled: true, capacityAlertEnabled: false, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: 70, updatedBy: 'admin',
    });
  });

  it('the defaults answer (nothing saved yet: updatedAt and updatedBy null) is read as the values it says', () => {
    const settings = fromServer(serverAnswer({ autoBalancingEnabled: true, capacityAlertEnabled: true, capacityThresholdPercent: 85, updatedAt: null, updatedBy: null }));
    assert.equal(settings.capacityThresholdPercent, 85);
    assert.equal(settings.updatedBy, null);
  });

  it('an incomplete or invalid answer is null: nothing is guessed or defaulted', () => {
    const bad = [
      null, undefined, 'x', 5, [], {},
      serverAnswer({ autoBalancingEnabled: undefined }),
      serverAnswer({ capacityAlertEnabled: 'true' }),
      serverAnswer({ crossDepartmentAssistanceEnabled: null }),
      serverAnswer({ capacityThresholdPercent: undefined }),
      serverAnswer({ capacityThresholdPercent: '85' }),
      serverAnswer({ capacityThresholdPercent: 85.5 }),
      serverAnswer({ capacityThresholdPercent: 0 }),
      serverAnswer({ capacityThresholdPercent: 101 }),
    ];
    for (const answer of bad) assert.equal(fromServer(answer), null, JSON.stringify(answer));
  });

  it('the form shows the threshold as text and the three switches as they are saved', () => {
    assert.deepEqual(toForm(fromServer(serverAnswer())), {
      autoBalancingEnabled: true, capacityAlertEnabled: false, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: '70',
    });
  });
});

describe('the threshold input', () => {
  it('accepts whole numbers from 1 to 100 (surrounding spaces are ignored)', () => {
    for (const ok of ['1', '50', '85', '99', '100', ' 85 ', '085', 70]) {
      const result = parseThreshold(ok);
      assert.ok(result.valid, String(ok));
      assert.equal(result.value, Number(String(ok).trim()));
    }
  });

  it('refuses 0, 101 and above, negatives, decimals, blanks, text, exponents and non-ASCII digits', () => {
    for (const bad of ['0', '00', '101', '150', '999', '1000', '-1', '-50', '85.5', '85.0', '0.5', '', '   ', 'abc', '8a', '1e2', '+85', null, undefined, {}, '٨٥']) {
      const result = parseThreshold(bad);
      assert.equal(result.valid, false, String(bad));
      assert.equal(result.message, SYSTEM_SETTINGS_MESSAGES.invalidThreshold);
    }
  });
});

describe('the save request', () => {
  const form = { autoBalancingEnabled: true, capacityAlertEnabled: false, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: '90' };

  it('carries all four required fields, the threshold as a number', () => {
    assert.deepEqual(buildRequest(form), {
      request: { autoBalancingEnabled: true, capacityAlertEnabled: false, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: 90 },
    });
  });

  it('is an error, with nothing to send, when the threshold is not valid', () => {
    for (const bad of ['', '0', '101', '12.5', 'x']) {
      const built = buildRequest({ ...form, capacityThresholdPercent: bad });
      assert.equal(built.request, undefined);
      assert.equal(built.error, SYSTEM_SETTINGS_MESSAGES.invalidThreshold);
    }
  });

  it('Save is only offered for a real change: editing a switch or the threshold makes the form differ, putting it back does not', () => {
    const saved = fromServer(serverAnswer());
    const base = toForm(saved);
    assert.equal(isDirty(saved, base), false);
    assert.equal(isDirty(saved, { ...base, autoBalancingEnabled: false }), true);
    assert.equal(isDirty(saved, { ...base, capacityAlertEnabled: true }), true);
    assert.equal(isDirty(saved, { ...base, crossDepartmentAssistanceEnabled: false }), true);
    assert.equal(isDirty(saved, { ...base, capacityThresholdPercent: '71' }), true);
    assert.equal(isDirty(saved, { ...base, capacityThresholdPercent: '70' }), false);
    assert.equal(isDirty(saved, { ...base, capacityThresholdPercent: 'abc' }), true, 'an invalid entry still differs (the error is shown, Save stays off)');
    assert.equal(isDirty(null, base), false);
  });
});

describe('loading', () => {
  it('resolves with the saved values', async () => {
    const settings = await loadSystemSettings({ fetcher: async () => serverAnswer() });
    assert.equal(settings.capacityThresholdPercent, 70);
    assert.equal(settings.capacityAlertEnabled, false);
  });

  it('a failed request rejects: the defaults are never shown in its place', async () => {
    for (const status of [401, 403, 500, 0]) {
      await assert.rejects(loadSystemSettings({ fetcher: async () => { throw Object.assign(new Error('x'), { status }); } }), (e) => e.status === status);
    }
  });

  it('an unreadable answer rejects too', async () => {
    for (const answer of [null, {}, serverAnswer({ capacityThresholdPercent: 'x' }), 'oops']) {
      await assert.rejects(loadSystemSettings({ fetcher: async () => answer }), (e) => e.status === 0);
    }
  });
});

describe('saving', () => {
  const form = { autoBalancingEnabled: false, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: false, capacityThresholdPercent: '60' };

  it('sends all four fields and resolves with what the server confirmed (not with what was typed)', async () => {
    let sent;
    const confirmed = await saveSystemSettings(form, { send: async (request) => { sent = request; return serverAnswer({ autoBalancingEnabled: false, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: false, capacityThresholdPercent: 61 }); } });
    assert.deepEqual(sent, { autoBalancingEnabled: false, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: false, capacityThresholdPercent: 60 });
    assert.equal(confirmed.capacityThresholdPercent, 61, 'the confirmed value is what is shown next');
  });

  it('an invalid threshold is refused before any request is made', async () => {
    let calls = 0;
    for (const bad of ['0', '101', '85.5', '', 'abc']) {
      await assert.rejects(saveSystemSettings({ ...form, capacityThresholdPercent: bad }, { send: async () => { calls += 1; return serverAnswer(); } }), (e) => e.status === 400 && e.message === SYSTEM_SETTINGS_MESSAGES.invalidThreshold);
    }
    assert.equal(calls, 0);
  });

  it('a refused or failed save rejects, so it is never reported as a success', async () => {
    for (const status of [400, 401, 403, 500]) {
      await assert.rejects(saveSystemSettings(form, { send: async () => { throw Object.assign(new Error('x'), { status }); } }), (e) => e.status === status);
    }
  });

  it('an answer that is not a complete settings answer is a failure, not a success', async () => {
    await assert.rejects(saveSystemSettings(form, { send: async () => ({ ok: true }) }), (e) => e.status === 0);
    await assert.rejects(saveSystemSettings(form, { send: async () => null }), (e) => e.status === 0);
  });
});

describe('error messages', () => {
  it('403 says only administrators can do this, whatever the server text', () => {
    assert.equal(systemSettingsErrorMessage({ status: 403, message: 'whatever' }, 'fallback'), SYSTEM_SETTINGS_MESSAGES.forbidden);
  });

  it('a short 400 message from the server is shown; anything else is the fallback (no internals, no long text)', () => {
    assert.equal(systemSettingsErrorMessage({ status: 400, message: 'capacityThresholdPercent must be a whole number from 1 to 100' }, 'fallback'), 'capacityThresholdPercent must be a whole number from 1 to 100');
    assert.equal(systemSettingsErrorMessage({ status: 400, message: 'x'.repeat(500) }, 'fallback'), 'fallback');
    assert.equal(systemSettingsErrorMessage({ status: 500, message: 'NullPointerException at Foo.java:12' }, 'fallback'), 'fallback');
    assert.equal(systemSettingsErrorMessage(null, 'fallback'), 'fallback');
    assert.equal(systemSettingsErrorMessage({ status: 400, message: '   ' }, 'fallback'), 'fallback');
  });
});

describe('the API calls', () => {
  const realFetch = globalThis.fetch;
  const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  let calls;

  beforeEach(() => {
    calls = [];
    const store = { token: 'jwt-token', username: 'admin', role: 'ADMIN' };
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      writable: true,
      value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
    });
    Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/settings/system' } } });
  });

  afterEach(() => {
    globalThis.fetch = realFetch;
    if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
    if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
  });

  const answer = (status, body) => {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options });
      return { ok: status >= 200 && status < 300, status, json: async () => body };
    };
  };

  it('GET /api/system-settings with the token', async () => {
    answer(200, serverAnswer());
    const result = await getSystemSettings();
    assert.equal(result.capacityThresholdPercent, 70);
    assert.equal(calls[0].url, '/api/system-settings');
    assert.equal(calls[0].options.method, undefined);
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
  });

  it('PUT /api/system-settings with exactly the four fields as JSON', async () => {
    answer(200, serverAnswer({ capacityThresholdPercent: 90 }));
    const saved = await saveSystemSettings({ autoBalancingEnabled: true, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: '90' }, { send: updateSystemSettings });
    assert.equal(saved.capacityThresholdPercent, 90);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/system-settings');
    assert.equal(calls[0].options.method, 'PUT');
    assert.deepEqual(JSON.parse(calls[0].options.body), { autoBalancingEnabled: true, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: 90 });
  });

  it('a 403 is an error with the permission message and never signs the user out; a 401 does', async () => {
    answer(403, {});
    await assert.rejects(loadSystemSettings(), (e) => e.status === 403);
    assert.equal(globalThis.localStorage.getItem('token'), 'jwt-token');
    assert.equal(globalThis.window.location.href, '/settings/system');
    answer(401, {});
    await assert.rejects(loadSystemSettings());
    assert.equal(globalThis.window.location.href, '/login');
  });

  it('a 400 from the server on save carries the server message through to the error text', async () => {
    answer(400, { message: 'capacityThresholdPercent must be a whole number from 1 to 100' });
    await assert.rejects(saveSystemSettings({ autoBalancingEnabled: true, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: '50' }, { send: updateSystemSettings }),
      (e) => e.status === 400 && systemSettingsErrorMessage(e, 'fallback') === 'capacityThresholdPercent must be a whole number from 1 to 100');
  });

  it('a 500 is an error and the user-facing text is the fixed fallback', async () => {
    answer(500, {});
    await assert.rejects(saveSystemSettings({ autoBalancingEnabled: true, capacityAlertEnabled: true, crossDepartmentAssistanceEnabled: true, capacityThresholdPercent: '50' }, { send: updateSystemSettings }),
      (e) => systemSettingsErrorMessage(e, SYSTEM_SETTINGS_MESSAGES.saveFailed) === SYSTEM_SETTINGS_MESSAGES.saveFailed);
  });
});
