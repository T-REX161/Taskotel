import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  MAX_STALE_MS,
  STALE_LIMIT_MESSAGE,
  decideWorkloadRefresh,
  isAccessDenied,
  selectedStaffAfterRefresh,
} from './workloadRefresh.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const ok = (data) => ({ ok: true, data });
const fail = (status, message = 'failed') => {
  const error = new Error(message);
  if (status) error.status = status;
  return { ok: false, error };
};
const network = () => ({ ok: false, error: new TypeError('Failed to fetch') });
const T0 = 1_000_000;
const rows = [{ staffId: 'A1', department: 'HOUSEKEEPING' }];
const departments = [{ code: 'HOUSEKEEPING', name: 'Housekeeping', managed: true }];
const staff = [{ id: 'a1', email: 'a@x.test' }];

const decide = (over) => decideWorkloadRefresh({
  role: 'MANAGER', isBackground: true, now: T0 + 30_000, lastSuccessAt: T0,
  outcome: { workload: ok(rows), departments: ok(departments), staff: ok(staff) }, ...over,
});

describe("a Manager's Workload page after a refresh", () => {
  it('a successful refresh replaces everything and ends any stale or error state', () => {
    const d = decide({});
    assert.equal(d.action, 'replace');
    assert.deepEqual(d.workload, rows);
    assert.deepEqual(d.departments, departments);
    assert.deepEqual(d.allStaff, staff);
    assert.equal(d.staleSince, null);
    assert.equal(d.clearError, true, 'a page that was cleared or stale heals itself');
  });

  it('a revoked grant: the next successful refresh simply no longer contains the department', () => {
    const after = decide({ outcome: { workload: ok([]), departments: ok([departments[0]]), staff: ok([]) } });
    assert.equal(after.action, 'replace');
    assert.deepEqual(after.workload, []);
    assert.deepEqual(after.departments.map((d) => d.code), ['HOUSEKEEPING']);
  });

  it('PARTIAL: rows arrive but the department list fails: the list is dropped, never kept from an older refresh', () => {
    const d = decide({ outcome: { workload: ok(rows), departments: fail(500), staff: ok(staff) } });
    assert.equal(d.action, 'replace');
    assert.deepEqual(d.workload, rows);
    assert.deepEqual(d.departments, [], 'an empty list, not null (null would keep the old one)');
    assert.deepEqual(d.allStaff, staff);
  });

  it('PARTIAL: rows arrive but the staff list fails: the staff list is dropped too (its contact details may be of a revoked department)', () => {
    const d = decide({ outcome: { workload: ok(rows), departments: ok(departments), staff: network() } });
    assert.deepEqual(d.allStaff, []);
    assert.deepEqual(d.departments, departments);
  });

  it('PARTIAL: both side requests fail: fresh rows alone, nothing older beside them', () => {
    const d = decide({ outcome: { workload: ok(rows), departments: network(), staff: fail(500) } });
    assert.deepEqual([d.departments, d.allStaff], [[], []]);
  });

  it('PARTIAL: the rows fail while the others succeed: nothing is applied from the others (the rows are the anchor)', () => {
    const d = decide({ outcome: { workload: network(), departments: ok(departments), staff: ok(staff) } });
    assert.equal(d.action, 'keep-stale');
    assert.equal(d.departments, undefined);
    assert.equal(d.allStaff, undefined);
  });

  it('a refusal (403) clears everything at once, quietly or not, and shows the server\'s message', () => {
    for (const isBackground of [true, false]) {
      const d = decide({ isBackground, outcome: { workload: fail(403, 'Your account is not assigned to an active department.'), departments: fail(403), staff: fail(403) } });
      assert.equal(d.action, 'clear');
      assert.deepEqual([d.workload, d.departments, d.allStaff], [[], [], []]);
      assert.equal(d.staleSince, null);
      assert.equal(d.error, 'Your account is not assigned to an active department.');
    }
    assert.equal(isAccessDenied({ status: 403 }), true);
    for (const other of [null, undefined, {}, { status: 500 }, { status: 401 }, new Error('x')]) assert.equal(isAccessDenied(other), false);
  });

  it('a transient failure (network error, 5xx) keeps the last data, marked stale since the last success', () => {
    for (const workload of [network(), fail(500), fail(502), fail(503), fail(404), fail(429)]) {
      const d = decide({ now: T0 + 31_000, outcome: { workload, departments: ok(departments), staff: ok(staff) } });
      assert.equal(d.action, 'keep-stale');
      assert.equal(d.staleSince, T0, 'the notice says when the data on screen was last good');
    }
  });

  it('the stale data is cleared after two minutes without a successful refresh, and not a moment before', () => {
    assert.equal(MAX_STALE_MS, 120_000);
    const outcome = { workload: network(), departments: network(), staff: network() };
    assert.equal(decide({ now: T0 + MAX_STALE_MS - 1, outcome }).action, 'keep-stale');
    const cleared = decide({ now: T0 + MAX_STALE_MS, outcome });
    assert.equal(cleared.action, 'clear');
    assert.deepEqual([cleared.workload, cleared.departments, cleared.allStaff], [[], [], []]);
    assert.equal(cleared.error, STALE_LIMIT_MESSAGE);
    assert.equal(decide({ now: T0 + 10 * MAX_STALE_MS, outcome }).action, 'clear');
  });

  it('a later success after a clear or a stale period restores the page', () => {
    const healed = decide({ now: T0 + 5 * MAX_STALE_MS });
    assert.equal(healed.action, 'replace');
    assert.equal(healed.staleSince, null);
    assert.equal(healed.clearError, true);
  });

  it('the first load or a Retry that fails shows the error panel (as it always did); with nothing loaded yet a quiet failure changes nothing', () => {
    assert.deepEqual(decide({ isBackground: false, lastSuccessAt: 0, outcome: { workload: network(), departments: ok([]), staff: ok([]) } }),
      { action: 'error', error: 'Failed to fetch' });
    assert.deepEqual(decide({ isBackground: false, outcome: { workload: fail(500, 'A server error occurred.'), departments: ok([]), staff: ok([]) } }),
      { action: 'error', error: 'A server error occurred.' });
    assert.equal(decide({ lastSuccessAt: 0, outcome: { workload: network(), departments: network(), staff: network() } }).action, 'keep');
  });

  it('an ended session still goes to the sign-in page', () => {
    assert.equal(decide({ outcome: { workload: fail(0, 'Session expired or unauthorized. Redirecting to login...'), departments: network(), staff: network() } }).action, 'redirect');
  });

  it('the role is read case-insensitively', () => {
    assert.equal(decide({ role: 'manager', outcome: { workload: fail(403), departments: ok([]), staff: ok([]) } }).action, 'clear');
  });
});

describe('Admin and Staff: every case behaves as the page always has', () => {
  for (const role of ['ADMIN', 'STAFF', '', null, undefined]) {
    it(`role ${String(role)}: nothing is cleared, nothing is marked stale`, () => {
      // a quiet failure of the rows: keep everything, whatever the status
      for (const workload of [network(), fail(500), fail(403)]) {
        const d = decideWorkloadRefresh({ role, isBackground: true, now: T0 + 10 * MAX_STALE_MS, lastSuccessAt: T0, outcome: { workload, departments: ok([]), staff: ok([]) } });
        assert.deepEqual(d, { action: 'keep' });
      }
      // a failed first load or Retry: the error panel
      assert.equal(decideWorkloadRefresh({ role, isBackground: false, now: 1, lastSuccessAt: 0, outcome: { workload: fail(403, 'no'), departments: ok([]), staff: ok([]) } }).action, 'error');
      // a quiet refresh whose rows arrive: a failed department or staff request keeps what is shown (null); a foreground one empties it
      const quiet = decideWorkloadRefresh({ role, isBackground: true, now: 1, lastSuccessAt: 1, outcome: { workload: ok(rows), departments: fail(500), staff: network() } });
      assert.equal(quiet.action, 'replace');
      assert.deepEqual([quiet.departments, quiet.allStaff], [null, null]);
      assert.equal(quiet.clearError, false);
      const first = decideWorkloadRefresh({ role, isBackground: false, now: 1, lastSuccessAt: 0, outcome: { workload: ok(rows), departments: fail(500), staff: ok(null) } });
      assert.deepEqual([first.departments, first.allStaff], [[], []]);
      // everything arrives: replaced as before
      const all = decideWorkloadRefresh({ role, isBackground: true, now: 1, lastSuccessAt: 1, outcome: { workload: ok(rows), departments: ok(departments), staff: ok(staff) } });
      assert.deepEqual([all.workload, all.departments, all.allStaff], [rows, departments, staff]);
    });
  }

  it('a successful answer that is not a list is treated as an empty one (as before)', () => {
    const d = decideWorkloadRefresh({ role: 'ADMIN', isBackground: true, now: 1, lastSuccessAt: 1, outcome: { workload: ok(null), departments: ok(departments), staff: ok(staff) } });
    assert.deepEqual(d.workload, []);
  });
});

describe("a Manager's open staff details after a refresh", () => {
  const selected = { id: 'a1', name: 'Ana' };

  it('stay open while the person is among the refreshed rows (any letter case)', () => {
    assert.equal(selectedStaffAfterRefresh('MANAGER', selected, rows), selected);
    assert.equal(selectedStaffAfterRefresh('MANAGER', { id: 'A1' }, [{ staffId: 'a1' }]).id, 'A1');
  });

  it('close when the person is no longer there (their department was revoked) or when there are no rows at all', () => {
    assert.equal(selectedStaffAfterRefresh('MANAGER', selected, [{ staffId: 'b2' }]), null);
    assert.equal(selectedStaffAfterRefresh('MANAGER', selected, []), null);
    assert.equal(selectedStaffAfterRefresh('MANAGER', selected, null), null);
    assert.equal(selectedStaffAfterRefresh('MANAGER', { name: 'No id' }, rows), null);
    assert.equal(selectedStaffAfterRefresh('MANAGER', null, rows), null);
  });

  it('a record built from a row (id = the row\'s staffId) is kept or closed the same way', () => {
    assert.ok(selectedStaffAfterRefresh('MANAGER', { id: 'A1', fullName: 'Ana' }, rows));
    assert.equal(selectedStaffAfterRefresh('MANAGER', { id: 'ZZ' }, rows), null);
  });

  it('for anyone else nothing changes', () => {
    for (const role of ['ADMIN', 'STAFF', null]) {
      assert.equal(selectedStaffAfterRefresh(role, selected, []), selected);
      assert.equal(selectedStaffAfterRefresh(role, null, rows), null);
    }
  });
});

describe('WorkloadPage wiring of the refresh policy', () => {
  const page = read('..', 'pages', 'WorkloadPage.jsx');

  it('settles each request on its own and lets the decision module decide, for every role', () => {
    assert.ok(page.includes("import { decideWorkloadRefresh, selectedStaffAfterRefresh } from '../utils/workloadRefresh';"));
    assert.ok(/Promise\.all\(\[\s*settle\(workloadPromise\),\s*settle\(departmentsPromise,[^)]*\),\s*settle\(staffPromise,/.test(page));
    assert.ok(page.includes('const decision = decideWorkloadRefresh({'));
    assert.ok(page.includes('lastSuccessAt: lastFetchRef.current'));
  });

  it('applies a clear or a replace to all parts together, and closes the details of a person who is gone', () => {
    assert.ok(page.includes("if (decision.action === 'replace' || decision.action === 'clear') {"));
    assert.ok(page.includes('setWorkload(decision.workload);'));
    assert.ok(page.includes('setSelectedStaff((current) => selectedStaffAfterRefresh(role, current, decision.workload));'));
    assert.ok(page.includes('if (decision.error) setError(decision.error);'));
    assert.ok(page.includes("if (decision.clearError) setError('');"), 'a later success heals a cleared page');
  });

  it('shows stale data with a notice (the existing banner, with Refresh now) and only while it is stale', () => {
    assert.ok(page.includes('{staleSince && ('));
    assert.ok(page.includes('testId="workload-stale-notice"'));
    assert.ok(page.includes("Couldn't refresh. Showing data from"));
    assert.ok(page.includes("retryLabel=\"Refresh now\""));
    assert.ok(page.includes('setStaleSince(decision.staleSince);'));
  });

  it('only the page\'s own refresh changed: the Cross-Department Assistance panel is untouched by this policy', () => {
    const panel = read('..', 'components', 'workload', 'CrossDepartmentSupportPanel.jsx');
    assert.ok(!panel.includes('workloadRefresh') && !panel.includes('decideWorkloadRefresh'));
    assert.ok(page.includes('<CrossDepartmentSupportPanel'));
  });
});
