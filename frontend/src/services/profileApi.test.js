import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { getProfile, uploadProfilePicture, removeProfilePicture } from './api.js';
import { profileFor, getCurrentProfileSnapshot, resetCurrentProfile, setCurrentProfile } from '../utils/currentUserProfile.js';
import {
  PROFILE_PICTURE_MESSAGES,
  displayedProfileImage,
  initialProfilePictureState,
  profilePictureErrorMessage,
  profilePictureReducer,
} from '../utils/profilePicture.js';

const realFetch = globalThis.fetch;
const realStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const realWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');

beforeEach(() => {
  const store = { token: 'jwt-token', username: 'maria', role: 'MANAGER' };
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    writable: true,
    value: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } },
  });
  Object.defineProperty(globalThis, 'window', { configurable: true, writable: true, value: { location: { href: '/profile' } } });
  resetCurrentProfile();
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (realStorage) Object.defineProperty(globalThis, 'localStorage', realStorage); else delete globalThis.localStorage;
  if (realWindow) Object.defineProperty(globalThis, 'window', realWindow); else delete globalThis.window;
});

/** fetch that answers once with `status` and the JSON `body`, recording what was sent. */
const respond = (status, body) => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
  return calls;
};

const pngFile = () => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])], 'me.png', { type: 'image/png' });
const NEW_URL = 'https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v2/taskotel/profile-pictures/user_new';

describe('profile picture API calls', () => {
  it('upload sends the file as multipart form data (field "file") with the sign-in token and no JSON content type', async () => {
    const calls = respond(200, { username: 'maria', role: 'MANAGER', profileImageUrl: NEW_URL });
    const file = pngFile();
    const result = await uploadProfilePicture(file);

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/profile/picture');
    assert.equal(calls[0].options.method, 'POST');
    assert.ok(calls[0].options.body instanceof FormData);
    assert.equal(calls[0].options.body.get('file').name, 'me.png');
    assert.equal(calls[0].options.headers.Authorization, 'Bearer jwt-token');
    assert.equal(calls[0].options.headers['Content-Type'], undefined, 'the browser sets the multipart boundary');
    assert.equal(result.profileImageUrl, NEW_URL);
  });

  it('upload failure keeps the server status and its plain message', async () => {
    respond(413, { status: 413, message: 'The image is too large. Images must be 5 MB or smaller.' });
    const error = await uploadProfilePicture(pngFile()).then(() => assert.fail('expected a failure'), (e) => e);
    assert.equal(error.status, 413);
    assert.equal(profilePictureErrorMessage(error, PROFILE_PICTURE_MESSAGES.uploadFailed), 'The image is too large. Images must be 5 MB or smaller.');
  });

  it('JSON requests still send the JSON content type', async () => {
    const calls = respond(200, { username: 'maria', profileImageUrl: null });
    await getProfile();
    assert.equal(calls[0].url, '/api/profile');
    assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
  });

  it('remove sends DELETE to the same endpoint', async () => {
    const calls = respond(200, { username: 'maria', profileImageUrl: null });
    const result = await removeProfilePicture();
    assert.equal(calls[0].url, '/api/profile/picture');
    assert.equal(calls[0].options.method, 'DELETE');
    assert.equal(result.profileImageUrl, null);
  });
});

describe('the profile page flow, using the same functions the page calls', () => {
  /** The page's handleSave: start, upload, share the new profile, finish (or report the failure). */
  const save = async (state, dispatch) => {
    dispatch({ type: 'uploadStart' });
    try {
      setCurrentProfile(await uploadProfilePicture(state.file));
      dispatch({ type: 'uploadSuccess' });
    } catch (error) {
      dispatch({ type: 'uploadFailure', error: profilePictureErrorMessage(error, PROFILE_PICTURE_MESSAGES.uploadFailed) });
    }
  };
  const harness = () => {
    let state = initialProfilePictureState;
    return { get state() { return state; }, dispatch: (action) => { state = profilePictureReducer(state, action); } };
  };

  it('upload success: the updated image is displayed everywhere the shared profile is used', async () => {
    setCurrentProfile({ username: 'maria', profileImageUrl: 'https://img/old' });
    const page = harness();
    page.dispatch({ type: 'select', file: pngFile(), previewUrl: 'blob:preview' });
    assert.equal(displayedProfileImage(page.state, 'https://img/old'), 'blob:preview');

    respond(200, { username: 'maria', role: 'MANAGER', profileImageUrl: NEW_URL });
    await save(page.state, page.dispatch);

    const saved = profileFor(getCurrentProfileSnapshot(), 'maria').profileImageUrl;
    assert.equal(saved, NEW_URL, 'header and profile page read this shared value');
    assert.equal(displayedProfileImage(page.state, saved), NEW_URL);
    assert.equal(page.state.success, PROFILE_PICTURE_MESSAGES.uploaded);
  });

  it('upload failure: the saved picture is unchanged and the reason is shown', async () => {
    setCurrentProfile({ username: 'maria', profileImageUrl: 'https://img/old' });
    const page = harness();
    page.dispatch({ type: 'select', file: pngFile(), previewUrl: 'blob:preview' });

    respond(502, { status: 502, message: 'The image service is not responding. Your current picture was kept; please try again.' });
    await save(page.state, page.dispatch);

    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').profileImageUrl, 'https://img/old');
    assert.equal(page.state.error, 'The image service is not responding. Your current picture was kept; please try again.');
    assert.equal(page.state.phase, 'selected', 'the preview stays so it can be saved again');
  });

  it('a network failure shows the network message', async () => {
    const page = harness();
    page.dispatch({ type: 'select', file: pngFile(), previewUrl: 'blob:preview' });
    globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
    await save(page.state, page.dispatch);
    assert.equal(page.state.error, PROFILE_PICTURE_MESSAGES.network);
  });
});
