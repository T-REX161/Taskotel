import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { keepIfSame, singleFlight, startPolling } from './polling.js';

const fakeDoc = () => {
  const listeners = new Set();
  return { visibilityState: 'visible', addEventListener: (_, fn) => listeners.add(fn), removeEventListener: (_, fn) => listeners.delete(fn), count: () => listeners.size, fire(state) { this.visibilityState = state; listeners.forEach((fn) => fn()); } };
};
const fakeTimers = () => {
  const active = new Map(); let id = 0;
  return { setTimer: (fn) => { active.set(++id, fn); return id; }, clearTimer: (i) => active.delete(i), tick: () => [...active.values()].forEach((fn) => fn()), count: () => active.size };
};

describe('dashboard background refresh', () => {
  it('polls while visible, goes quiet while hidden, refreshes at once on return, and cleans up', () => {
    const doc = fakeDoc(); const timers = fakeTimers(); let calls = 0;
    const stop = startPolling({ refresh: () => { calls++; }, intervalMs: 30000, doc, ...timers });
    assert.equal(timers.count(), 1); assert.equal(doc.count(), 1);
    timers.tick(); timers.tick(); assert.equal(calls, 2);
    doc.fire('hidden'); assert.equal(timers.count(), 0, 'no timer while the tab is hidden'); timers.tick(); assert.equal(calls, 2);
    doc.fire('visible'); assert.equal(calls, 3, 'refreshes immediately on return'); assert.equal(timers.count(), 1);
    stop(); assert.equal(timers.count(), 0); assert.equal(doc.count(), 0, 'timer and listener are removed on unmount');
    doc.fire('visible'); assert.equal(calls, 3);
  });

  it('does not start a timer when the page starts hidden', () => {
    const doc = fakeDoc(); doc.visibilityState = 'hidden'; const timers = fakeTimers();
    startPolling({ refresh() {}, intervalMs: 1, doc, ...timers }); assert.equal(timers.count(), 0);
  });

  it('singleFlight skips calls while one is running and allows the next one after it finishes', async () => {
    let runs = 0; let release; const gate = new Promise((r) => { release = r; });
    const guarded = singleFlight(async () => { runs++; await gate; });
    const first = guarded(); await guarded(); await guarded(); assert.equal(runs, 1, 'overlapping calls are skipped');
    release(); await first; await guarded(); assert.equal(runs, 2);
    const failing = singleFlight(async () => { throw new Error('x'); });
    await assert.rejects(failing()); await assert.rejects(failing(), /x/, 'a failure does not leave the guard stuck');
  });

  it('keepIfSame keeps the old object for identical data', () => {
    const a = { status: 'ready', data: [1] }; assert.equal(keepIfSame(a, { status: 'ready', data: [1] }), a);
    const b = { status: 'ready', data: [2] }; assert.equal(keepIfSame(a, b), b);
  });

  it('the Dashboard refreshes every 30s, silently, from every source once, and keeps loaded data when a refresh fails', () => {
    const page = fs.readFileSync(new URL('../pages/Dashboard.jsx', import.meta.url), 'utf8');
    assert.ok(page.includes('const DASHBOARD_REFRESH_MS = 30000;') && page.includes('startPolling({ refresh: () => refreshRef.current(true), intervalMs: DASHBOARD_REFRESH_MS })'));
    assert.ok(page.includes("if (!silent) setError('Unable to load dashboard data. Please try again.');"), 'a failed silent refresh never shows the error screen');
    assert.ok(page.includes("prev.status === 'ready' ? prev : { status: 'error', data: prev.data }"), 'failed refresh keeps loaded data');
    assert.ok(page.includes("...(getUserRole() !== 'STAFF' ? [loadStaffWorkload(), loadRecommendations(), loadTasks()] : [])"), 'Staff never refresh org-wide sources');
    assert.ok(page.includes('refreshRef.current(false);') && page.includes('return startPolling('), 'initial load on mount and cleanup on unmount');
  });
});
