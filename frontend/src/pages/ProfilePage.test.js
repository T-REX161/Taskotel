import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');

const page = read('ProfilePage.jsx');
const avatar = read('..', 'components', 'common', 'UserAvatar.jsx');
const header = read('..', 'components', 'layout', 'Header.jsx');
const staffTable = read('..', 'components', 'staff', 'StaffTable.jsx');
const usersTable = read('..', 'components', 'users', 'UsersTable.jsx');
const staffDetails = read('..', 'components', 'staff', 'StaffDetailsModal.jsx');
const hook = read('..', 'hooks', 'useCurrentUserProfile.js');

describe('profile page: the avatar opens the picture editor', () => {
  it('has no upload section in the header any more', () => {
    for (const gone of ['Change Profile Picture', 'More picture actions', 'Remove picture', 'Save picture', 'type="file"', 'uploadProfilePicture', 'removeProfilePicture', 'PROFILE_IMAGE_ACCEPT']) {
      assert.ok(!page.includes(gone), gone + ' should not be on the page');
    }
    assert.ok(!page.includes('JPG, PNG or WebP, up to 5 MB'), 'no format instructions in the header');
  });

  it('makes the avatar itself the button that opens the editor, with a tooltip and a camera indicator on hover', () => {
    const head = page.slice(page.indexOf('aria-label="Profile summary"'), page.indexOf('title="Personal Information"'));
    assert.ok(head.includes('title="Edit profile picture"') && head.includes('aria-label="Edit profile picture"'));
    assert.ok(head.includes('setEditorOpen(true)') && head.includes('<CameraIcon') && head.includes('group-hover:'));
    assert.ok(head.includes('rounded-full') && head.includes('<UserAvatar'));
    assert.ok(page.includes('<ProfilePictureEditor') && page.includes('savedImageUrl={savedImageUrl}'));
  });

  it("shares the server's updated profile when the editor is done, so the header and navbar change at once", () => {
    const done = page.slice(page.indexOf('const handlePictureDone'), page.indexOf('const status = accountStatus'));
    assert.ok(done.includes('setCurrentProfile(updated)') && done.includes('showNotice(message)') && done.includes('setEditorOpen(false)'));
    assert.ok(page.includes('role="status"') && page.includes('{noticeMessage}'));
  });

  it('keeps the existing profile details and branding', () => {
    assert.ok(page.includes('Taskotel — Hotel Operations, Simplified'));
    assert.ok(page.includes('accessScopeFor(role)') && page.includes('Email verification:'));
  });
});

describe('profile pictures across Taskotel', () => {
  it('the avatar falls back to the default (initials) when there is no picture or it fails to load', () => {
    assert.ok(avatar.includes('if (imageUrl && !failed)') && avatar.includes('onError={() => setFailed(true)}'));
    assert.ok(avatar.includes('{initials}') && avatar.includes('object-cover'));
  });

  it('the navbar shows the signed-in user\'s picture from the shared, once-loaded profile', () => {
    assert.ok(header.includes('useCurrentUserProfile()') && header.includes('imageUrl={profile?.profileImageUrl}') && header.includes('initials={initials}'));
    assert.ok(hook.includes('useSyncExternalStore') && hook.includes('loadCurrentProfile({ username })') && hook.includes('[username]'));
  });

  it('the staff list, user list and staff details use the picture from the existing list data', () => {
    assert.ok(staffTable.includes('<UserAvatar') && staffTable.includes('staff.profileImageUrl'));
    assert.ok(usersTable.includes('<UserAvatar') && usersTable.includes('imageUrl={user.profileImageUrl}'));
    assert.ok(staffDetails.includes('<UserAvatar') && staffDetails.includes('imageUrl={staff.profileImageUrl}'));
    for (const source of [staffTable, usersTable, staffDetails]) {
      assert.ok(!source.includes('getProfile('), 'no extra request per row');
    }
  });
});
