import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { avatarInitials } from '../../utils/profilePicture.js';

// The application-wide profile picture: one shared avatar (UserAvatar) everywhere a person is shown, fed by the person's existing
// profileImageUrl from the API (staffProfileImageUrl on assignment and support rows), with initials as the fallback.
const SRC = fileURLToPath(new URL('../../', import.meta.url));
const read = (...parts) => readFileSync(join(SRC, ...parts), 'utf8');
const avatar = read('components', 'common', 'UserAvatar.jsx');

// ── the shared component ────────────────────────────────────────────────────────────────────────────────────

test('shared avatar: photo when there is a URL, initials when there is none or the photo fails, always a circle', () => {
  assert.match(avatar, /if \(imageUrl && !failed\) \{/);
  assert.ok(avatar.includes('onError={() => setFailedUrl(imageUrl)}'), 'a failed load falls back to the initials');
  assert.ok(avatar.includes('const failed = Boolean(imageUrl) && failedUrl === imageUrl;'), 'the failure belongs to that URL: a changed picture gets a fresh try');
  assert.doesNotMatch(avatar, /useEffect/, 'no effect that could undo an instant failure');
  assert.ok(avatar.includes('rounded-full shrink-0'), 'always circular');
  assert.ok(avatar.includes('${photoClassName} object-cover'), 'the photo is cropped to fill the circle');
  assert.ok(avatar.includes('{initials ?? avatarInitials(name)}'), 'initials default to the shared helper');
  assert.ok(avatar.includes('data-avatar="photo"') && avatar.includes('data-avatar="initials"'));
});

test('shared avatar: size presets for tables, lists, cards and headers', () => {
  for (const [size, cls] of [['xs', 'w-7 h-7'], ['sm', 'w-8 h-8'], ['md', 'w-9 h-9'], ['lg', 'w-10 h-10'], ['xl', 'w-11 h-11'], ['2xl', 'w-12 h-12']]) {
    assert.match(avatar, new RegExp(`${size === '2xl' ? "'2xl'" : size}: '${cls}`), size);
  }
});

test('initials: "First Last" -> FL, a single name -> its first two letters, nothing -> "?"', () => {
  assert.equal(avatarInitials('Sasuke Uchiha'), 'SU');
  assert.equal(avatarInitials('  Sofia   del Marino '), 'SM');
  assert.equal(avatarInitials('rahul'), 'RA');
  assert.equal(avatarInitials(''), '?');
  assert.equal(avatarInitials(null), '?');
});

// ── every place a person is shown ───────────────────────────────────────────────────────────────────────────

const LOCATIONS = [
  ['components/layout/Header.jsx', 'imageUrl={profile?.profileImageUrl}'],
  ['components/users/UsersTable.jsx', 'imageUrl={user.profileImageUrl}'],
  ['components/staff/StaffTable.jsx', 'imageUrl={staff.profileImageUrl'],
  ['components/staff/StaffDetailsModal.jsx', 'imageUrl={staff.profileImageUrl}'],
  ['pages/ProfilePage.jsx', '<UserAvatar'],
  ['components/tasks/AssignTaskModal.jsx', 'imageUrl={staff.profileImageUrl}'],
  ['pages/AssignmentsPage.jsx', 'imageUrl={assignment.staffProfileImageUrl}'],
  ['pages/WorkloadPage.jsx', 'imageUrl={item.profileImageUrl || staffMatch?.profileImageUrl}'],
  ['components/dashboard/StaffWorkloadList.jsx', 'imageUrl={row.profileImageUrl}'],
  ['components/workload/support/SupportPlanBuilder.jsx', 'imageUrl={person.profileImageUrl}'],
  ['components/workload/support/SupportHistory.jsx', 'imageUrl={row.staffProfileImageUrl}'],
  ['components/workload/support/SupportEndDialog.jsx', 'imageUrl={row.staffProfileImageUrl}'],
];

test('every audited location renders the shared avatar from the person\'s existing profile picture URL', () => {
  for (const [file, source] of LOCATIONS) {
    const text = read(...file.split('/'));
    assert.ok(text.includes('<UserAvatar') || text.includes('<Avatar'), `${file} uses the shared avatar`);
    assert.ok(text.includes(source), `${file}: ${source}`);
  }
  // the Workload page appears twice (desktop table and phone cards), both from the same source
  assert.equal(read('pages', 'WorkloadPage.jsx').split('imageUrl={item.profileImageUrl || staffMatch?.profileImageUrl}').length - 1, 2);
  assert.ok(read('pages', 'WorkloadPage.jsx').includes('profileImageUrl: item.profileImageUrl || null,'), 'the details dialog gets the picture too');
  assert.ok(read('components', 'workload', 'CrossDepartmentSupportPanel.jsx').includes('profileImageUrl: s.profileImageUrl || null'));
  assert.ok(read('components', 'workload', 'support', 'SupportPlanBuilder.jsx').includes('imageUrl={person?.profileImageUrl}'), 'the plan list too');
});

test('no component looks for picture fields the API never sends, or draws its own person photo', () => {
  const files = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (/\.jsx$/.test(name)) files.push(full);
    }
  };
  walk(SRC);
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    assert.doesNotMatch(text, /staffMatch\??\.(avatarUrl|profileImage\b)|staff\.profileImage \|\||\.profileImage \|\| [a-z]+\.avatarUrl/, file);
  }
});

test('status indicators are kept: a selected or active person\'s photo gets a ring, their initials keep the green fill', () => {
  const modal = read('components', 'tasks', 'AssignTaskModal.jsx');
  assert.ok(modal.includes("photoClassName={isSelected ? 'ring-2 ring-emerald-500 ring-offset-1' : ''}"));
  assert.ok(modal.includes("isSelected ? 'bg-emerald-600 text-white shadow-xs' : 'bg-emerald-100 text-emerald-800'"));
  const builder = read('components', 'workload', 'support', 'SupportPlanBuilder.jsx');
  assert.ok(builder.includes("photoClassName={active ? 'ring-2 ring-emerald-500 ring-offset-1' : ''}"));
  const history = read('components', 'workload', 'support', 'SupportHistory.jsx');
  assert.ok(history.includes("photoClassName={stage === 'active' ? 'ring-2 ring-emerald-500 ring-offset-1' : ''}"));
});
