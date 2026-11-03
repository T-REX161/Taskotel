import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CROP_MAX_ZOOM, CROP_MIN_ZOOM, CROP_OUTPUT_SIZE, clampZoom, outputSideFor } from '../utils/cropImage.js';
import { validateProfileImageFile, PROFILE_PICTURE_MESSAGES } from '../utils/profilePicture.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (...parts) => fs.readFileSync(path.join(here, ...parts), 'utf8');
const editor = read('..', 'components', 'profile', 'ProfilePictureEditor.jsx');
const cropSource = read('..', 'utils', 'cropImage.js');
const pkg = JSON.parse(read('..', '..', 'package.json'));

describe('zoom limits', () => {
  it('never goes below the cover size or above the maximum, and ignores junk', () => {
    assert.equal(clampZoom(0.2), CROP_MIN_ZOOM);
    assert.equal(clampZoom(99), CROP_MAX_ZOOM);
    assert.equal(clampZoom(2.5), 2.5);
    assert.equal(clampZoom(NaN), CROP_MIN_ZOOM);
    assert.equal(CROP_MIN_ZOOM, 1, 'zoom 1 = the picture just covers the crop square');
    assert.ok(CROP_MAX_ZOOM > 1 && CROP_MAX_ZOOM <= 5);
  });
});

describe('cropped output size', () => {
  it("is the cropped area's own side, capped at 512, never upscaled", () => {
    assert.equal(outputSideFor({ width: 2000, height: 2000 }), CROP_OUTPUT_SIZE);
    assert.equal(outputSideFor({ width: 300, height: 300 }), 300);
    assert.equal(outputSideFor({ width: 300.4, height: 299.6 }), 300);
    assert.equal(outputSideFor(null), 0);
    assert.equal(outputSideFor({ width: 0, height: 10 }), 0);
  });
});

describe('file validation (before anything is read)', () => {
  const file = (name, type, size) => ({ name, type, size });
  it('accepts JPG, JPEG, PNG and WebP up to 5 MB', () => {
    for (const [n, t] of [['a.jpg', 'image/jpeg'], ['a.JPEG', 'image/jpeg'], ['a.png', 'image/png'], ['a.webp', 'image/webp']]) {
      assert.equal(validateProfileImageFile(file(n, t, 1000)), '');
    }
    assert.equal(validateProfileImageFile(file('a.png', 'image/png', 5 * 1024 * 1024)), '');
  });
  it('rejects other formats and files over 5 MB with clear messages', () => {
    assert.equal(validateProfileImageFile(file('a.gif', 'image/gif', 1000)), PROFILE_PICTURE_MESSAGES.unsupported);
    assert.equal(validateProfileImageFile(file('a.pdf', 'application/pdf', 1000)), PROFILE_PICTURE_MESSAGES.unsupported);
    assert.equal(validateProfileImageFile(file('a.png', 'image/png', 5 * 1024 * 1024 + 1)), PROFILE_PICTURE_MESSAGES.tooLarge);
  });
});

describe('profile picture editor', () => {
  it('uses react-easy-crop (a declared dependency) with a square, round, scroll-zoom crop and the zoom limits', () => {
    assert.ok(pkg.dependencies['react-easy-crop']);
    assert.ok(editor.includes("from 'react-easy-crop'") && editor.includes('aspect={1}') && editor.includes('cropShape="round"'));
    assert.ok(editor.includes('zoomWithScroll') && editor.includes('minZoom={CROP_MIN_ZOOM}') && editor.includes('maxZoom={CROP_MAX_ZOOM}'));
    assert.ok(editor.includes('objectFit="cover"'), 'the picture always covers the frame');
  });

  it('has no zoom slider, range input or plus/minus buttons', () => {
    assert.ok(!editor.includes('type="range"'));
    assert.ok(!editor.includes('>+<') && !editor.includes('>−<'));
  });

  it('shows the title, instruction, requirements and actions', () => {
    for (const text of ['Edit Profile Picture', 'Scroll to zoom · Drag to reposition', 'Adjust how your picture appears across Taskotel.', 'Image Actions', 'JPG, JPEG, PNG, WebP', 'Maximum size: 5 MB', 'Square crop · Circular avatar', 'Change Picture', 'Remove Picture', 'Cancel', 'Save Picture']) {
      assert.ok(editor.includes(text), text);
    }
  });

  it('keeps the page from scrolling under the crop area', () => {
    assert.ok(editor.includes('touch-none') && editor.includes('overscroll-contain'));
    assert.ok(editor.includes("document.body.style.overflow = 'hidden'"));
  });

  it('validates a chosen file before reading it, and a new file resets crop and zoom', () => {
    const change = editor.slice(editor.indexOf('const handleFileChange'), editor.indexOf('const handleSave'));
    assert.ok(change.indexOf('validateProfileImageFile(file)') < change.indexOf('URL.createObjectURL(file)'));
    assert.ok(change.includes('setCrop({ x: 0, y: 0 })') && change.includes('setZoom(CROP_MIN_ZOOM)'));
    assert.ok(editor.includes('URL.revokeObjectURL(url)'));
  });

  it('uploads the cropped file (never the original) through the existing endpoint, and only on Save', () => {
    const save = editor.slice(editor.indexOf('const handleSave'), editor.indexOf('const handleRemove'));
    assert.ok(save.includes('getCroppedFile(source.url, pixels)') && save.includes('uploadProfilePicture(file)'));
    assert.ok(!save.includes('source.file'), 'the original file is never uploaded');
    assert.ok(save.includes('onDone(updated'));
    assert.ok(!editor.slice(editor.indexOf('const handleFileChange'), editor.indexOf('const handleSave')).includes('uploadProfilePicture'));
  });

  it('asks for confirmation before removing, then uses the existing removal endpoint', () => {
    assert.ok(editor.includes('setConfirmingRemove(true)') && editor.includes('role="alertdialog"') && editor.includes('Remove profile picture?'));
    const remove = editor.slice(editor.indexOf('const handleRemove'), editor.indexOf('const dismiss'));
    assert.ok(remove.includes('await removeProfilePicture()') && remove.includes('onDone(updated'));
    assert.ok(editor.includes('{savedImageUrl && ('), 'remove is only offered when there is a picture');
  });

  it('cancel, Escape and backdrop clicks close without saving; nothing closes while busy', () => {
    assert.ok(editor.includes("event.key !== 'Escape' || busyRef.current") && editor.includes('onClick={dismiss}'));
    assert.ok(editor.includes('if (!busy) onClose()'));
    assert.ok(editor.includes('disabled={busy || !source || !pixels || !changed}'), 'Save only when there is something to save');
  });
});

describe('cropping is real pixels, not CSS', () => {
  it('draws the selected pixel rectangle onto a canvas and encodes it', () => {
    assert.ok(cropSource.includes('context.drawImage(image, pixelCrop.x, pixelCrop.y, pixelCrop.width, pixelCrop.height, 0, 0, side, side)'));
    assert.ok(cropSource.includes('canvas.toBlob') && cropSource.includes('new File([blob]'));
    assert.ok(cropSource.includes('image.crossOrigin'), 'remote pictures are fetched with CORS so the canvas stays readable');
  });
});

describe('editor modal layout', () => {
  it('is a wide modal capped at 85vh, with a two-column body and a footer that never scrolls away', () => {
    assert.ok(editor.includes('max-w-[800px]') && editor.includes('max-h-[85vh]') && editor.includes('flex flex-col'));
    assert.ok(editor.includes('md:grid-cols-[minmax(0,1fr)_17rem]') && editor.includes('grid-cols-1'), 'two columns from md, one on mobile');
    assert.ok(editor.includes('flex-1 min-h-0 overflow-y-auto'), 'only the content area can scroll');
    const footer = editor.slice(editor.indexOf('Footer: always visible'));
    assert.ok(footer.includes('shrink-0') && footer.includes('border-t') && footer.indexOf('Cancel') < footer.indexOf('Save Picture'));
  });

  it('sizes the square crop area from the viewport height so it always fits', () => {
    assert.ok(editor.includes('calc(85vh-230px)') && editor.includes('w-[var(--crop)] h-[var(--crop)]'));
  });

  it('keeps the actions and information in the right column', () => {
    const right = editor.slice(editor.indexOf('Actions and information'), editor.indexOf('Footer: always visible'));
    for (const text of ['Scroll to zoom · Drag to reposition', 'Change Picture', 'Remove Picture', 'Image Actions', 'JPG, JPEG, PNG, WebP', 'Maximum size: 5 MB', 'Square crop · Circular avatar']) {
      assert.ok(right.includes(text), text);
    }
  });
});

describe('compact requirements', () => {
  it('shows the requirements as three single-line rows, with no separate card', () => {
    const rows = editor.slice(editor.indexOf('Requirements: compact'), editor.indexOf('{error && <p role="alert"'));
    assert.equal((rows.match(/<li /g) || []).length, 3);
    assert.equal((rows.match(/whitespace-nowrap/g) || []).length, 3, 'each row stays on one line');
    assert.ok(!rows.includes('border') && !rows.includes('bg-gray-50'), 'no bordered or filled container');
    assert.ok(editor.includes('>Image Requirements<'));
  });
});

describe('final polish', () => {
  it('guards against duplicate requests synchronously, not only through state', () => {
    assert.ok(editor.includes('const inFlight = useRef(false)'));
    const save = editor.slice(editor.indexOf('const handleSave'), editor.indexOf('const handleRemove'));
    assert.ok(save.includes('if (inFlight.current || busy') && save.includes('inFlight.current = true'));
    const remove = editor.slice(editor.indexOf('const handleRemove'), editor.indexOf('const dismiss'));
    assert.ok(remove.includes('if (inFlight.current || busy) return;'));
  });

  it('draws a crisp circular boundary and has visible keyboard focus states', () => {
    assert.ok(editor.includes("border: '2px solid rgba(255, 255, 255, 0.95)'"));
    assert.ok((editor.match(/focus-visible:ring-2/g) || []).length >= 4);
    assert.ok(editor.includes('tabIndex={-1}') && editor.includes('dialogRef.current?.focus()'));
  });

  it('keeps the helper text readable and the right column balanced against the crop area', () => {
    assert.ok(editor.includes('text-gray-600') && editor.includes('md:self-stretch') && editor.includes('flex flex-col justify-center gap-4') && !editor.includes('md:my-auto') && !editor.includes('justify-between'));
  });
});

describe('right column is one compact, centred stack', () => {
  it('orders helper text, Image Actions, Change, Remove, Image Requirements and its three rows', () => {
    const right = editor.slice(editor.indexOf('Actions and information'), editor.indexOf('Footer: always visible'));
    const order = ['Scroll to zoom', '>Image Actions<', 'Change Picture', 'Remove Picture', '>Image Requirements<', 'JPG, JPEG, PNG, WebP', 'Maximum size: 5 MB', 'Square crop · Circular avatar'].map((t) => right.indexOf(t));
    assert.ok(order.every((i) => i >= 0) && order.every((v, i) => i === 0 || v > order[i - 1]), order.join(','));
  });
});
