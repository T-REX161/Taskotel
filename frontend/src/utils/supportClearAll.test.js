import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { whyCannotAdd } from './supportPlan.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');
const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
const panel = read('components', 'workload', 'CrossDepartmentSupportPanel.jsx');

test('Clear all sits in the Support history heading row (no row of its own), only for people who may archive, and asks first', () => {
  const at = panel.indexOf('<span>Support history (');
  const summary = panel.slice(panel.lastIndexOf('<summary', at), panel.indexOf('</summary>', at));
  assert.ok(summary.includes('data-testid="clear-history"') && summary.includes('canEnd && clearableRows.length > 0'), 'in the heading row, for Admin and Manager');
  assert.ok(summary.split('hidden group-open:').length - 1 >= 2, 'shown only while the history is expanded (the button and its confirmation)');
  assert.ok(summary.includes('event.preventDefault()') && summary.includes('event.stopPropagation()'), 'a click never opens or closes the list');
  assert.ok(summary.includes('Remove {clearableRows.length}?') && summary.includes('Remove all'), 'it asks before removing');
  assert.ok(panel.includes('(history.data || []).filter((row) => canArchiveSupport(row))'), 'only ended, archivable records');
  assert.ok(history.includes('!historyOnly && canEnd && onClearAll'), 'the collapsed history never adds a row of its own');
});

test('Clear all uses the same archive call as each row, counts refusals and reloads from the server', () => {
  assert.ok(panel.includes('await archiveSupportAssignment(row.id)'), 'the existing archive endpoint, one record at a time');
  assert.ok(panel.includes('failed += 1') && panel.includes('could not be removed'), 'a refusal is counted and reported');
  assert.ok(panel.includes('loadHistory();') && panel.includes('archiveInFlight.current'), 'reloaded, and never run twice at once');
  assert.ok(panel.includes('onClearAll={handleClearAll}'), 'the tabbed view offers it too');
  assert.ok(!/delete|DELETE/.test(panel.slice(panel.indexOf('const handleClearAll'), panel.indexOf('const canEnd'))), 'it archives, it never deletes');
});

test('The Support history opens and closes smoothly, and still respects reduced motion', () => {
  const css = read('index.css');
  assert.ok(panel.includes('group tk-collapsible'), 'the history list uses the smooth details');
  assert.ok(css.includes('.tk-collapsible::details-content') && css.includes('transition: height') && css.includes('content-visibility'), 'height and fade, with the hidden state animated too');
  assert.ok(css.includes('interpolate-size: allow-keywords') && css.includes('.tk-collapsible[open]::details-content { height: auto; opacity: 1; }'));
  assert.ok(css.indexOf('.tk-collapsible') < css.indexOf('reduced-motion strategy'), 'the shared reduced-motion rule comes after it and shortens it');
});


test('A person who cannot be added is given the real reason of their department, not always the reserve message', () => {
  const options = (note) => ({ tasks: [{ id: 't1' }], maxHelpers: 2, donors: [{ code: 'HK', maxLendable: 0, overrideLendable: 0, note, staff: [{ staffId: 's1' }] }] });
  assert.equal(whyCannotAdd(options('Lending anyone would push it to or past its own capacity limit.'), [], 'HK', 's1'), 'Lending anyone would push it to or past its own capacity limit.');
  assert.equal(whyCannotAdd(options('Its only free staff member stays in reserve.'), [], 'HK', 's1'), 'Its only free staff member stays in reserve.');
  assert.match(whyCannotAdd(options(null), [], 'HK', 's1'), /cannot lend anyone else without going past its limit or its reserve/);
});
