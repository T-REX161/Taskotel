import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readSession, tokenExpiry } from './session.js';

const b64 = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
const makeToken = (payload) => `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64(payload)}.signature`;

const NOW = Date.UTC(2026, 0, 1);
const inFuture = Math.floor(NOW / 1000) + 3600;
const inPast = Math.floor(NOW / 1000) - 1;

describe('reading the stored sign-in', () => {
  it('nothing stored is anonymous', () => {
    assert.deepEqual(readSession({ token: null, role: null, now: NOW }), { status: 'anonymous', role: null });
    assert.deepEqual(readSession({ token: '', role: 'ADMIN', now: NOW }), { status: 'anonymous', role: null });
  });

  it('a token with a future expiry and a known role is active', () => {
    assert.deepEqual(readSession({ token: makeToken({ sub: 'a', exp: inFuture }), role: 'STAFF', now: NOW }), { status: 'active', role: 'STAFF' });
  });

  it('a token whose own expiry has passed is expired (the role is not used)', () => {
    assert.deepEqual(readSession({ token: makeToken({ exp: inPast }), role: 'ADMIN', now: NOW }), { status: 'expired', role: null });
    assert.equal(readSession({ token: makeToken({ exp: Math.floor(NOW / 1000) }), role: 'ADMIN', now: NOW }).status, 'expired', 'expires at the instant');
  });

  it('a malformed token is invalid, whatever the role says', () => {
    for (const token of ['garbage', 'a.b', 'a.b.c.d', '..', 'a..c', `${b64({})}.!!!.sig`, `${b64({})}.${Buffer.from('not json').toString('base64url')}.sig`, `${b64({})}.${b64('text')}.sig`, `${b64({})}.${b64(null)}.sig`]) {
      assert.equal(readSession({ token, role: 'ADMIN', now: NOW }).status, 'invalid', token);
    }
  });

  it('a missing or unrecognised role is invalid, never a default role', () => {
    const token = makeToken({ exp: inFuture });
    for (const role of [null, undefined, '', 'admin', 'ROOT', 'ADMIN ']) {
      assert.deepEqual(readSession({ token, role, now: NOW }), { status: 'invalid', role: null }, String(role));
    }
  });

  it('a token with no expiry is not rejected by the browser (the server decides) but still needs a known role', () => {
    assert.equal(readSession({ token: makeToken({ sub: 'a' }), role: 'MANAGER', now: NOW }).status, 'active');
    assert.equal(tokenExpiry(makeToken({ sub: 'a' })), null);
    assert.equal(tokenExpiry('nope'), undefined);
    assert.equal(tokenExpiry(undefined), undefined);
  });
});
