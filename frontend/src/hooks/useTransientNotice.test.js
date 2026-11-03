import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NOTICE_DURATION_MS } from './useTransientNotice.js';
import { PROFILE_PICTURE_MESSAGES } from '../utils/profilePicture.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const hook = fs.readFileSync(path.join(here, 'useTransientNotice.js'), 'utf8');
const page = fs.readFileSync(path.join(here, '..', 'pages', 'ProfilePage.jsx'), 'utf8');
const editor = fs.readFileSync(path.join(here, '..', 'components', 'profile', 'ProfilePictureEditor.jsx'), 'utf8');

describe('self-dismissing profile picture notice', () => {
  it('uses the agreed wording and a 3-4 second duration', () => {
    assert.equal(PROFILE_PICTURE_MESSAGES.uploaded, 'Profile picture updated successfully.');
    assert.ok(NOTICE_DURATION_MS >= 3000 && NOTICE_DURATION_MS <= 4000);
  });

  it('restarts and cleans up its timer through an effect', () => {
    assert.ok(hook.includes('setTimeout(() => setState(null), duration)') && hook.includes('return () => clearTimeout(timer)'));
    assert.ok(hook.includes('counter.current += 1') && hook.includes('[state, duration]'), 'a new id every time, so the same text still restarts the timer');
  });

  it('is used by the profile page: shown on success, cleared when the editor opens, never reloading the page', () => {
    assert.ok(page.includes('useTransientNotice()') && page.includes('showNotice(message)') && page.includes('clearNotice(); setEditorOpen(true)'));
    assert.ok(!page.includes('location.reload') && !page.includes('window.location'));
    assert.ok(!page.includes('setPictureMessage'));
  });

  it('only a successful upload or removal reaches the notice (failures stay inside the editor)', () => {
    const save = editor.slice(editor.indexOf('const handleSave'), editor.indexOf('const handleRemove'));
    assert.ok(save.indexOf('uploadProfilePicture(file)') < save.indexOf('onDone(updated'));
    assert.ok(save.includes('catch (err)') && !/catch \(err\)[^}]*onDone/.test(save));
  });
});

describe('every profile success message dismisses itself after 3 seconds', () => {
  it('uses the same self-dismissing mechanism for display name, email, phone, picture and password', () => {
    assert.equal(NOTICE_DURATION_MS, 3000);
    for (const wiring of ['nameNotice.show(DISPLAY_NAME_MESSAGES.saved)', 'emailNotice.show(EMAIL_CHANGE_MESSAGES.changed)', 'phoneNotice.show(PHONE_MESSAGES.changed)', 'showNotice(message)']) {
      assert.ok(page.includes(wiring), wiring);
    }
    for (const stale of ['setNameSaved', 'setEmailChanged', 'setPhoneChanged', 'useState(false);\n  const [phoneDialogOpen']) {
      assert.ok(!page.includes(stale), stale + ' (the old flags that never cleared) must be gone');
    }
    assert.equal((page.match(/useTransientNotice\(\)/g) || []).length, 4, 'one notice per message area, each with its own timer');
  });

  it('only success messages use the timers; errors keep their own state and stay until handled', () => {
    assert.ok(page.includes('setNameError(') && page.includes("role=\"alert\""));
    assert.ok(!/nameNotice\.show\([^)]*[Ee]rror/.test(page) && !/(emailNotice|phoneNotice|showNotice)\.show\([^)]*[Ee]rror/.test(page));
  });
});
