import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { loadCrossDepartmentStatus, peekCrossDepartmentStatus, resetCrossDepartmentStatus } from './crossDepartmentStatus.js';
import { WorkloadPageSkeleton } from '../components/common/PageSkeletons.js';

// The Cross-Department Assistance switch, read once and shared by the Workload page (for its skeleton) and the support panel.
const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const deferred = () => { let resolve; let reject; const promise = new Promise((res, rej) => { resolve = res; reject = rej; }); return { promise, resolve, reject }; };

beforeEach(() => resetCrossDepartmentStatus());

describe('the shared reader', () => {
  it('one request for the page and the panel: a running read is shared, and the panel reuses the answer', async () => {
    let requests = 0;
    const pending = deferred();
    const fetcher = () => { requests += 1; return pending.promise; };
    const page = loadCrossDepartmentStatus({ force: true, fetcher });
    const strictModeSecondRun = loadCrossDepartmentStatus({ force: true, fetcher });
    pending.resolve({ crossDepartmentAssistanceEnabled: false });
    assert.equal(await page, 'off');
    assert.equal(await strictModeSecondRun, 'off');
    assert.equal(await loadCrossDepartmentStatus({ fetcher }), 'off', 'the panel mounting later');
    assert.equal(requests, 1);
    assert.equal(peekCrossDepartmentStatus(), 'off');
  });

  it('later checks are fresh: force starts a new read once nothing is running', async () => {
    let enabled = false;
    const fetcher = async () => ({ crossDepartmentAssistanceEnabled: enabled });
    assert.equal(await loadCrossDepartmentStatus({ force: true, fetcher }), 'off');
    enabled = true;
    assert.equal(await loadCrossDepartmentStatus({ fetcher }), 'off', 'reused without force');
    assert.equal(await loadCrossDepartmentStatus({ force: true, fetcher }), 'on', 'the 30-second check, Refresh, returning to the tab');
    assert.equal(peekCrossDepartmentStatus(), 'on');
  });

  it('a failed read rejects and keeps the last known value (so nothing flips on a network error)', async () => {
    assert.equal(await loadCrossDepartmentStatus({ force: true, fetcher: async () => ({ crossDepartmentAssistanceEnabled: true }) }), 'on');
    await assert.rejects(loadCrossDepartmentStatus({ force: true, fetcher: async () => { throw new Error('network'); } }));
    assert.equal(peekCrossDepartmentStatus(), 'on');
    resetCrossDepartmentStatus();
    await assert.rejects(loadCrossDepartmentStatus({ fetcher: async () => { throw new Error('network'); } }));
    assert.equal(peekCrossDepartmentStatus(), null, 'a failure is never remembered as an answer');
  });

  it('an unclear answer is "unknown" (the panel then shows in full, as before)', async () => {
    assert.equal(await loadCrossDepartmentStatus({ force: true, fetcher: async () => ({}) }), 'unknown');
  });
});

describe('the Workload skeleton follows the switch', () => {
  const page = read('pages/WorkloadPage.jsx');
  const frame = 'min-w-0 bg-white rounded-2xl shadow-2xs border border-gray-100 px-4 pt-4 pb-5 sm:px-6 sm:pt-5 sm:pb-6 space-y-6';

  it('room for the panel only when it will be shown: on, or a failed read (where the panel shows in full)', () => {
    assert.ok(page.includes("showSupport={supportSetting === 'on' || supportSetting === 'unknown'}"));
    assert.ok(renderToStaticMarkup(h(WorkloadPageSkeleton, { showSupport: true })).includes(frame), 'ON: the panel frame');
    assert.ok(!renderToStaticMarkup(h(WorkloadPageSkeleton, { showSupport: false })).includes(frame), 'OFF or not known yet: no room reserved');
  });

  it('read once as the page opens, for Admin and Manager only; the last known value is used until it arrives', () => {
    assert.ok(page.includes("const canSeeSupport = ['ADMIN', 'MANAGER'].includes(getUserRole());"));
    assert.ok(page.includes("useState(() => (canSeeSupport ? peekCrossDepartmentStatus() || 'loading' : 'not-needed'))"), 'Staff never ask');
    assert.match(page, /if \(!canSeeSupport\) return undefined;\s*let active = true;\s*loadCrossDepartmentStatus\(\{ force: true \}\)/);
    assert.ok(page.includes(".catch(() => { if (active) setSupportSetting((prev) => (prev === 'loading' ? 'unknown' : prev)); });"), 'a failed read is handled');
    assert.ok(page.includes('return () => { active = false; };'), 'no update after the page goes away');
  });
});
