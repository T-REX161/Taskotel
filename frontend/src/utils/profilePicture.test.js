import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  PROFILE_IMAGE_ACCEPT,
  PROFILE_IMAGE_MAX_BYTES,
  PROFILE_PICTURE_MESSAGES,
  avatarInitials,
  displayedProfileImage,
  initialProfilePictureState,
  profilePictureErrorMessage,
  profilePictureReducer,
  validateProfileImageFile,
} from './profilePicture.js';

const file = (name, type, size = 1024) => ({ name, type, size });
const run = (...actions) => actions.reduce(profilePictureReducer, initialProfilePictureState);

describe('validateProfileImageFile (invalid file handling)', () => {
  it('accepts JPG, JPEG, PNG and WebP up to exactly 5 MB', () => {
    for (const f of [file('me.jpg', 'image/jpeg'), file('ME.JPEG', 'image/jpeg'), file('me.png', 'image/png'), file('me.webp', 'image/webp'),
      file('me.jpg', 'image/jpg'), file('me.png', ''), file('max.png', 'image/png', PROFILE_IMAGE_MAX_BYTES)]) {
      assert.equal(validateProfileImageFile(f), '', f.name);
    }
  });

  it('rejects other kinds of files by extension or type', () => {
    for (const f of [file('me.gif', 'image/gif'), file('me.svg', 'image/svg+xml'), file('me.pdf', 'application/pdf'), file('me', 'image/png'),
      file('me.png.exe', 'application/x-msdownload'), file('me.png', 'text/html'), file('me.heic', 'image/heic')]) {
      assert.equal(validateProfileImageFile(f), PROFILE_PICTURE_MESSAGES.unsupported, f.name);
    }
  });

  it('rejects files over 5 MB, empty files and no file', () => {
    assert.equal(validateProfileImageFile(file('big.jpg', 'image/jpeg', PROFILE_IMAGE_MAX_BYTES + 1)), PROFILE_PICTURE_MESSAGES.tooLarge);
    assert.equal(validateProfileImageFile(file('empty.png', 'image/png', 0)), PROFILE_PICTURE_MESSAGES.empty);
    assert.equal(validateProfileImageFile(null), PROFILE_PICTURE_MESSAGES.missing);
    assert.match(PROFILE_PICTURE_MESSAGES.tooLarge, /5 MB/);
  });

  it('the file picker offers the same four formats', () => {
    assert.equal(PROFILE_IMAGE_ACCEPT, '.jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp');
  });
});

describe('profile picture flow (preview, save, cancel)', () => {
  const chosen = { type: 'select', file: file('me.png', 'image/png'), previewUrl: 'blob:preview-1' };
  const saved = 'https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v1/old';

  it('image preview: a chosen file is shown locally before anything is uploaded', () => {
    const state = run(chosen);
    assert.equal(state.phase, 'selected');
    assert.equal(displayedProfileImage(state, saved), 'blob:preview-1');
    assert.equal(displayedProfileImage(initialProfilePictureState, saved), saved, 'without a choice the saved picture shows');
    assert.equal(displayedProfileImage(initialProfilePictureState, null), null, 'no picture: the default avatar shows');
  });

  it('cancel: drops the preview and shows the saved picture again', () => {
    const state = run(chosen, { type: 'cancel' });
    assert.deepEqual(state, { ...initialProfilePictureState });
    assert.equal(displayedProfileImage(state, saved), saved);
  });

  it('an invalid choice or an unreadable preview shows a message and selects nothing', () => {
    const invalid = run({ type: 'invalid', error: PROFILE_PICTURE_MESSAGES.tooLarge });
    assert.equal(invalid.phase, 'idle');
    assert.equal(invalid.error, PROFILE_PICTURE_MESSAGES.tooLarge);
    const unreadable = run(chosen, { type: 'previewFailed' });
    assert.equal(unreadable.phase, 'idle');
    assert.equal(unreadable.previewUrl, null);
    assert.equal(unreadable.error, PROFILE_PICTURE_MESSAGES.unreadable);
  });

  it('upload success: the loading state ends, the preview is released and a success message shows', () => {
    const uploading = run(chosen, { type: 'uploadStart' });
    assert.equal(uploading.phase, 'uploading');
    assert.equal(displayedProfileImage(uploading, saved), 'blob:preview-1', 'the preview stays while saving');
    assert.deepEqual(run(chosen, { type: 'cancel' }, { type: 'uploadStart' }).phase, 'idle', 'nothing to save after cancel');
    assert.equal(profilePictureReducer(uploading, { type: 'cancel' }), uploading, 'cannot cancel mid-upload');

    const done = profilePictureReducer(uploading, { type: 'uploadSuccess' });
    assert.equal(done.phase, 'idle');
    assert.equal(done.previewUrl, null);
    assert.equal(done.success, PROFILE_PICTURE_MESSAGES.uploaded);
    const newUrl = 'https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v2/new';
    assert.equal(displayedProfileImage(done, newUrl), newUrl, 'the updated picture is displayed after upload');
  });

  it('upload failure: an error shows, the preview stays for a retry, and the saved picture is unchanged', () => {
    const failed = run(chosen, { type: 'uploadStart' }, { type: 'uploadFailure', error: 'The image service is not responding.' });
    assert.equal(failed.phase, 'selected');
    assert.equal(failed.previewUrl, 'blob:preview-1');
    assert.equal(failed.error, 'The image service is not responding.');
    assert.equal(run(chosen, { type: 'uploadStart' }, { type: 'uploadFailure', error: 'x' }, { type: 'uploadStart' }).phase, 'uploading', 'can retry');
    assert.equal(run(chosen, { type: 'uploadStart' }, { type: 'uploadFailure', error: 'x' }, { type: 'cancel' }).phase, 'idle', 'or cancel');
  });

  it('remove: only from the idle state, with success and failure messages', () => {
    assert.equal(run({ type: 'removeStart' }).phase, 'removing');
    assert.equal(run(chosen, { type: 'removeStart' }).phase, 'selected', 'not while a new picture is previewed');
    assert.equal(run({ type: 'removeStart' }, { type: 'removeSuccess' }).success, PROFILE_PICTURE_MESSAGES.removed);
    assert.equal(run({ type: 'removeStart' }, { type: 'removeFailure', error: 'kept' }).error, 'kept');
  });
});

describe('profilePictureErrorMessage (upload failure messages)', () => {
  const withStatus = (status, message) => Object.assign(new Error(message), { status });

  it("shows the server's plain message for 400, 413, 415, 502 and 503", () => {
    for (const [status, message] of [[413, 'The image is too large. Images must be 5 MB or smaller.'], [415, 'Only JPG, JPEG, PNG or WebP images can be uploaded.'],
      [502, 'The image service is not responding. Your current picture was kept; please try again.'], [503, 'Profile pictures are not available right now.'], [400, 'Choose an image to upload.']]) {
      assert.equal(profilePictureErrorMessage(withStatus(status, message), 'fallback'), message);
    }
  });

  it('uses the network message when no answer came back, and the fallback for unusable messages', () => {
    assert.equal(profilePictureErrorMessage(new TypeError('Failed to fetch'), 'fallback'), PROFILE_PICTURE_MESSAGES.network);
    assert.equal(profilePictureErrorMessage(withStatus(500, ''), 'fallback'), 'fallback');
    assert.equal(profilePictureErrorMessage(withStatus(500, 'x'.repeat(201)), 'fallback'), 'fallback');
    assert.equal(profilePictureErrorMessage(null, 'fallback'), 'fallback');
  });
});

describe('avatarInitials (default avatar)', () => {
  it('uses first and last initials, or the first two letters', () => {
    assert.equal(avatarInitials('Dua Dino'), 'DD');
    assert.equal(avatarInitials('Maria Del Carmen Lopez'), 'ML');
    assert.equal(avatarInitials('admin'), 'AD');
    assert.equal(avatarInitials('  '), '?');
    assert.equal(avatarInitials(null, 'ST'), 'ST');
  });
});
