import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  getCurrentProfileSnapshot,
  loadCurrentProfile,
  profileFor,
  resetCurrentProfile,
  setCurrentProfile,
  subscribeCurrentProfile,
} from './currentUserProfile.js';

const profile = (username, profileImageUrl = null) => ({ username, role: 'STAFF', fullName: username, profileImageUrl });

beforeEach(() => resetCurrentProfile());

describe('current user profile cache (no repeated requests)', () => {
  it('loads once per user and reuses the result, including for calls made while the first is running', async () => {
    let requests = 0;
    const fetchProfile = async () => { requests += 1; return profile('maria'); };
    const [a, b] = await Promise.all([loadCurrentProfile({ username: 'maria', fetchProfile }), loadCurrentProfile({ username: 'maria', fetchProfile })]);
    await loadCurrentProfile({ username: 'maria', fetchProfile });
    assert.equal(requests, 1);
    assert.deepEqual(a, profile('maria'));
    assert.equal(a, b);
  });

  it('loads again for a different signed-in user, and never shows the previous user\'s picture', async () => {
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => profile('maria', 'https://img/maria') });
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'rocky'), null);
    await loadCurrentProfile({ username: 'rocky', fetchProfile: async () => profile('rocky') });
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'rocky').profileImageUrl, null);
  });

  it('an upload result replaces the cached profile and notifies every subscriber (header and profile page)', async () => {
    await loadCurrentProfile({ username: 'maria', fetchProfile: async () => profile('maria') });
    const seen = [];
    const stop = subscribeCurrentProfile(() => seen.push(getCurrentProfileSnapshot().profile?.profileImageUrl));
    setCurrentProfile(profile('maria', 'https://img/new'));
    stop();
    assert.deepEqual(seen, ['https://img/new']);
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria').profileImageUrl, 'https://img/new');
    setCurrentProfile(profile('maria', 'https://img/newer'));
    assert.equal(seen.length, 1, 'unsubscribed listeners are not called');
  });

  it('a failed load keeps the default avatar and does not throw', async () => {
    const result = await loadCurrentProfile({ username: 'maria', fetchProfile: async () => { throw Object.assign(new Error('boom'), { status: 500 }); } });
    assert.equal(result, null);
    assert.equal(profileFor(getCurrentProfileSnapshot(), 'maria'), null);
    assert.equal(getCurrentProfileSnapshot().loading, false);
    assert.equal(getCurrentProfileSnapshot().error.status, 500);
  });

  it('does nothing without a signed-in user, and force reloads on request', async () => {
    let requests = 0;
    const fetchProfile = async () => { requests += 1; return profile('maria'); };
    assert.equal(await loadCurrentProfile({ username: null, fetchProfile }), null);
    await loadCurrentProfile({ username: 'maria', fetchProfile });
    await loadCurrentProfile({ username: 'maria', fetchProfile, force: true });
    assert.equal(requests, 2);
  });
});
