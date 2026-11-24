import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { MANAGER_SCOPE_NOTICE, scopeOptionsToRole, supportPermissions } from './supportPlan.js';

// The server sends a Manager the figures of departments they manage only; a department without figures is not authorized
const managed = (code) => ({ code, name: code, freeStaff: 3, availableStaff: 3, waitingTasks: 0, maxLendable: 2, overrideLendable: 3, canLend: true, note: null, staff: [] });
const unauthorized = (code) => ({ code, name: code, freeStaff: null, availableStaff: null, waitingTasks: null, maxLendable: null, overrideLendable: null, canLend: null, note: null, staff: [{ staffId: 's1', name: 'Pat' }] });
const options = { receiving: { code: 'HOUSEKEEPING' }, tasks: [], donors: [managed('SECURITY'), unauthorized('FRONT_DESK')] };

describe('departments offered to a Manager', () => {
  it('a Manager is offered only the lending departments they manage', () => {
    assert.deepEqual(scopeOptionsToRole(options, 'MANAGER').donors.map((d) => d.code), ['SECURITY']);
  });

  it('an Admin keeps every lending department, and other roles are left unchanged', () => {
    assert.equal(scopeOptionsToRole(options, 'ADMIN').donors.length, 2);
    assert.equal(scopeOptionsToRole(options, 'STAFF'), options);
  });

  it('missing options are handled', () => {
    assert.equal(scopeOptionsToRole(null, 'MANAGER'), null);
    assert.deepEqual(scopeOptionsToRole({ donors: 'x' }, 'MANAGER'), { donors: 'x' });
  });

  it('a Manager may confirm and is told the scope; Staff may do nothing', () => {
    assert.equal(supportPermissions('MANAGER').canConfirm, true);
    assert.equal(supportPermissions('STAFF').canConfirm, false);
    assert.match(MANAGER_SCOPE_NOTICE, /only between departments you manage/);
  });
});
