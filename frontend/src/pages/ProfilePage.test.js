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

describe('profile page: change profile picture', () => {
  it('offers a Change Profile Picture action with a hidden picker limited to JPG, PNG and WebP', () => {
    assert.ok(page.includes('Change Profile Picture'));
    assert.ok(page.includes('type="file"') && page.includes('accept={PROFILE_IMAGE_ACCEPT}') && page.includes('className="hidden"'));
    assert.ok(page.includes('fileInputRef.current?.click()'));
  });

  it('validates the chosen file before previewing it, and previews it locally', () => {
    const change = page.slice(page.indexOf('const handleFileChange'), page.indexOf('const handleSave'));
    assert.ok(change.indexOf('validateProfileImageFile(file)') < change.indexOf('URL.createObjectURL(file)'), 'validation before preview');
    assert.ok(change.includes("dispatch({ type: 'invalid', error: problem })"));
    assert.ok(page.includes('src={picture.previewUrl}') && page.includes("onError={() => dispatch({ type: 'previewFailed' })}"));
  });

  it('releases every preview URL', () => {
    assert.ok(page.includes('URL.revokeObjectURL(url)') && page.includes('[picture.previewUrl]'));
  });

  it('shows Save and Cancel while previewing, with a loading state during upload', () => {
    assert.ok(page.includes('Save picture') && page.includes("dispatch({ type: 'cancel' })") && page.includes('Uploading…'));
    assert.ok(page.includes('disabled={busy}'));
  });

  it('uploads only on Save, then shares the new profile so the header updates without another request', () => {
    const save = page.slice(page.indexOf('const handleSave'), page.indexOf('const handleRemove'));
    assert.ok(save.includes('await uploadProfilePicture(picture.file)') && save.includes('setCurrentProfile(updated)'));
    assert.ok(save.includes("dispatch({ type: 'uploadFailure', error: profilePictureErrorMessage(error, PROFILE_PICTURE_MESSAGES.uploadFailed) })"));
    assert.ok(!page.slice(page.indexOf('const handleFileChange'), page.indexOf('const handleSave')).includes('uploadProfilePicture'), 'choosing never uploads');
  });

  it('shows success and failure feedback, and a remove action only when a picture exists', () => {
    assert.ok(page.includes('role="alert"') && page.includes('{picture.error}'));
    assert.ok(page.includes('role="status"') && page.includes('{picture.success}'));
    assert.ok(page.includes('{savedImageUrl && (') && page.includes('Remove picture') && page.includes('await removeProfilePicture()'));
  });

  it('keeps the existing profile details and branding', () => {
    assert.ok(page.includes('Taskotel — Hotel Operations, Simplified'));
    assert.ok(page.includes('Full Administrator Access') && page.includes('Email Verified'));
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
