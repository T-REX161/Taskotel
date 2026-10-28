import { describe, it, beforeEach, afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import { createUsernameChecker } from './usernameChecker.js';

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

describe('createUsernameChecker', () => {
  let updates, calls, taken, checker;
  const last = () => updates[updates.length - 1];
  // check() answers from `taken`, but only when the test lets it through (deferred) so ordering can be controlled
  const makeChecker = (check) => createUsernameChecker({ check, onUpdate: (u) => updates.push(u) });

  beforeEach(() => {
    mock.timers.enable({ apis: ['setTimeout'] });
    updates = []; calls = []; taken = new Set();
    checker = makeChecker(async (username) => { calls.push(username); return !taken.has(username); });
  });
  afterEach(() => { checker.dispose(); mock.timers.reset(); });

  it('shows "checking" at once and asks the server only after the debounce', async () => {
    checker.update('ayushmallick', 'Ayush Mallick');
    assert.equal(last().status, 'checking');
    mock.timers.tick(399);
    assert.deepEqual(calls, []);
    mock.timers.tick(1);
    await flush();
    assert.deepEqual(calls, ['ayushmallick']);
    assert.equal(last().status, 'available');
  });

  it('sends one request for a burst of keystrokes', async () => {
    for (const value of ['a', 'ay', 'ayu', 'ayush']) { checker.update(value + 'xx'); mock.timers.tick(100); }
    mock.timers.tick(400);
    await flush();
    assert.deepEqual(calls, ['ayushxx']);
  });

  it('reports a taken username with alternatives that were each confirmed free', async () => {
    taken = new Set(['ayushmallick', 'ayush.mallick']);
    checker.update('ayushmallick', 'Ayush Mallick');
    mock.timers.tick(400);
    await flush();
    assert.equal(last().status, 'taken');
    assert.deepEqual(last().suggestions, ['ayush_mallick', 'ayushmallick1', 'ayushmallick2']);
    assert.ok(!last().suggestions.some((s) => taken.has(s)));
  });

  it('drops alternatives whose own check failed instead of presenting them as available', async () => {
    taken = new Set(['nina']);
    checker.dispose();
    checker = makeChecker(async (username) => {
      if (username === 'nina1') throw new Error('boom');
      return !taken.has(username);
    });
    checker.update('nina');
    mock.timers.tick(400);
    await flush();
    assert.deepEqual(last().suggestions, ['nina2', 'nina3', 'nina01']);
  });

  it('shows an error, never "available", when the request fails', async () => {
    checker.dispose();
    checker = makeChecker(async () => { throw new Error('Network down'); });
    checker.update('ayushmallick');
    mock.timers.tick(400);
    await flush();
    assert.equal(last().status, 'error');
    assert.ok(updates.every((u) => u.status !== 'available'));
  });

  it('treats anything other than an explicit true as not available', async () => {
    checker.dispose();
    checker = makeChecker(async () => undefined);
    checker.update('ayushmallick');
    mock.timers.tick(400);
    await flush();
    assert.notEqual(last().status, 'available');
  });

  it('ignores a slow answer for an older username', async () => {
    const pending = {};
    checker.dispose();
    checker = makeChecker((username) => new Promise((resolve) => { pending[username] = resolve; }));
    checker.update('firstname');
    mock.timers.tick(400);
    await flush();
    checker.update('secondname');
    mock.timers.tick(400);
    await flush();
    pending.secondname(true);
    await flush();
    pending.firstname(false); // arrives late, after the field moved on
    await flush();
    assert.equal(last().username, 'secondname');
    assert.equal(last().status, 'available');
    assert.ok(!updates.some((u) => u.username === 'firstname' && u.status === 'taken'));
  });

  it('aborts the request for the previous value', async () => {
    let firstSignal;
    checker.dispose();
    checker = makeChecker((username, signal) => { firstSignal ||= signal; return new Promise(() => {}); });
    checker.update('firstname');
    mock.timers.tick(400);
    await flush();
    checker.update('secondname');
    assert.equal(firstSignal.aborted, true);
  });

  it('makes no request for empty or invalid usernames', async () => {
    checker.update('   ');
    assert.equal(last().status, 'idle');
    checker.update('two words');
    assert.equal(last().status, 'invalid');
    assert.equal(last().reason, 'space');
    mock.timers.tick(1000);
    await flush();
    assert.deepEqual(calls, []);
  });

  it('cancels a pending check when the username becomes invalid', async () => {
    checker.update('validname');
    checker.update('not valid');
    mock.timers.tick(1000);
    await flush();
    assert.deepEqual(calls, []);
  });
});
