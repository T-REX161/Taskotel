import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describeNeed, pressureUtilizationText } from './supportRecommendations.js';
import { donorUtilizationText } from './supportPlan.js';

const read = (...parts) => readFileSync(new URL(`../${parts.join('/')}`, import.meta.url), 'utf8');

test('describeNeed explains why support is suggested, from the server reason', () => {
  assert.equal(describeNeed({ needReason: 'HIGH_UTILIZATION' }), 'At or above its capacity limit');
  assert.equal(describeNeed({ needReason: 'BOTH' }), 'Over its capacity limit and short of free staff');
  assert.equal(describeNeed({ needReason: 'UNCOVERED_TASKS' }), 'More waiting tasks than free staff');
  assert.equal(describeNeed({ needReason: 'NONE' }), '');
  assert.equal(describeNeed(undefined), '');
});

test('pressureUtilizationText shows utilization, "No capacity", or nothing when the server sent no figures', () => {
  assert.equal(pressureUtilizationText({ utilizationPercent: 66.7 }), '66.7%');
  assert.equal(pressureUtilizationText({ utilizationPercent: 100 }), '100%');
  assert.equal(pressureUtilizationText({ noCapacity: true, utilizationPercent: null }), 'No capacity');
  assert.equal(pressureUtilizationText({}), null);
  assert.equal(pressureUtilizationText(null), null);
});

test('donorUtilizationText shows a donor\'s utilization and, when it can lend, the utilization after lending one', () => {
  assert.equal(donorUtilizationText({ utilizationPercent: 20, maxLendable: 1, utilizationAfterOne: 40 }), ' · 20% utilized (40% after lending one)');
  assert.equal(donorUtilizationText({ utilizationPercent: 90, maxLendable: 0, utilizationAfterOne: 180 }), ' · 90% utilized');
  assert.equal(donorUtilizationText({}), '');
  assert.equal(donorUtilizationText(null), '');
});

test('the impact preview and AnimatedNumber carry the capacity wiring', () => {
  assert.match(read('components', 'workload', 'support', 'SupportImpactPreview.jsx'), /export function riskVerdict/);
  assert.match(read('components', 'common', 'AnimatedNumber.jsx'), /decimals = 0/);
});
