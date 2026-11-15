import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// The person-details dialog (Users, Staff and Workload pages): a profile header, then Work and Contact information as compact rows.
const modal = readFileSync(new URL('./StaffDetailsModal.jsx', import.meta.url), 'utf8');

test('profile header: a large circular picture (shared avatar), the name, @username, a role badge and the department', () => {
  assert.match(modal, /<UserAvatar\s+imageUrl=\{staff\.profileImageUrl\}/, 'the existing picture, with the shared fallback to initials');
  assert.ok(modal.includes('className="mx-auto w-20 h-20 rounded-full shrink-0 ring-4 ring-white shadow-sm"'), '80px circle');
  assert.ok(modal.includes('<h2 id="staff-details-title"') && modal.includes("{staff.fullName || staff.name || 'Staff Details'}"));
  assert.ok(modal.includes("@{staff.username || 'staff'}"));
  assert.ok(modal.includes('roleBadgeClass(normalizeRole(roleLabel))'), 'the same role colours as the Users table');
  assert.ok(modal.includes('{departmentLabel}') && modal.includes('const departmentLabel = formatDepartment(staff.department);'));
});

test('work information: department, role, availability and account status as divided rows with icons', () => {
  const work = modal.slice(modal.indexOf('aria-labelledby="staff-details-work"'), modal.indexOf('aria-labelledby="staff-details-contact"'));
  for (const [icon, label] of [['DepartmentsIcon', 'Department'], ['UserIcon', 'Role'], ['ClockIcon', 'Availability'], ['CircleDotIcon', 'Account status']]) {
    assert.ok(work.includes(`icon={${icon}}`) && work.includes(`label="${label}"`), label);
  }
  assert.ok(work.includes('{formatDepartment(staff.department)}') && work.includes('{isUnassignedManager(staff) && <p'), 'the unassigned-Manager notice is kept');
  assert.ok(work.includes('getAvailabilityColor(staff.availability)') && work.includes('getStatusColor(staff.active)'), 'existing status colours');
  assert.ok(modal.includes('divide-y divide-gray-100 rounded-xl border border-gray-100'), 'subtle dividers instead of one card per field');
});

test('contact information: email and phone with icons; long values wrap; missing values say "Not provided"', () => {
  const contact = modal.slice(modal.indexOf('aria-labelledby="staff-details-contact"'));
  assert.ok(contact.includes('icon={MailIcon}') && contact.includes('icon={PhoneIcon}'));
  assert.ok(contact.includes('<span className="break-all" title={staff.email}>{staff.email}</span>'), 'a long email wraps instead of overflowing');
  assert.ok(contact.includes('break-all tabular-nums">{staff.phone}'));
  assert.equal((contact.match(/<NotProvided \/>/g) || []).length, 2);
  assert.ok(modal.includes('Not provided'));
});

test('dialog: accessible, responsive, both close controls work, and nothing is fetched', () => {
  assert.ok(modal.includes('role="dialog"') && modal.includes('aria-modal="true"') && modal.includes('aria-labelledby="staff-details-title"'));
  assert.ok(modal.includes('sm:max-w-md') && modal.includes('items-end sm:items-center') && modal.includes('overflow-y-auto'), 'sheet on a phone, centred card from sm up, scrolling body');
  assert.equal((modal.match(/onClick=\{onClose\}/g) || []).length, 3, 'backdrop, close icon and Close button');
  assert.ok(modal.includes('aria-label="Close modal"') && modal.includes('>\n            Close\n          </button>'));
  assert.ok(modal.includes('onClick={(e) => e.stopPropagation()}'), 'clicks inside do not close it');
  assert.doesNotMatch(modal, /services\/api|fetch\(/);
});
