import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  PROFILE_IMAGE_MAX_BYTES,
  PROFILE_PICTURE_MESSAGES,
  sniffImageType,
  validateProfileImageContent,
  validateProfileImageFile,
} from './profilePicture.js';

const JPEG = [0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1];
const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d];
const WEBP = [0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x45, 0x42, 0x50];
const GIF = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80, 0];
const text = (s) => [...new TextEncoder().encode(s)];

// A real File (Node has one), so slice().arrayBuffer() - the way the editor reads the first bytes - is what is tested
const fileOf = (name, type, bytes, size) => {
  const f = new File([new Uint8Array(bytes)], name, { type });
  return size === undefined ? f : Object.defineProperty(f, 'size', { value: size });
};

describe('sniffing the real image kind from the first bytes', () => {
  it('recognises JPEG, PNG and WebP and nothing else', () => {
    assert.equal(sniffImageType(JPEG), 'jpeg');
    assert.equal(sniffImageType(PNG), 'png');
    assert.equal(sniffImageType(WEBP), 'webp');
    assert.equal(sniffImageType(GIF), null);
    assert.equal(sniffImageType(text('<svg xmlns="http://www.w3.org/2000/svg"/>')), null);
    assert.equal(sniffImageType(text('%PDF-1.7')), null);
    assert.equal(sniffImageType([0x42, 0x4d, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]), null, 'BMP');
    assert.equal(sniffImageType([0xff, 0xd8]), null, 'too short to be a JPEG signature');
    assert.equal(sniffImageType([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45]), null, 'RIFF but WAVE, not WebP');
    assert.equal(sniffImageType(undefined), null);
  });
});

describe('content validation of a chosen file', () => {
  it('accepts valid JPG, JPEG, PNG and WebP files', async () => {
    assert.equal(await validateProfileImageContent(fileOf('a.jpg', 'image/jpeg', JPEG)), '');
    assert.equal(await validateProfileImageContent(fileOf('a.jpeg', 'image/jpeg', JPEG)), '');
    assert.equal(await validateProfileImageContent(fileOf('a.PNG', 'image/png', PNG)), '');
    assert.equal(await validateProfileImageContent(fileOf('a.webp', 'image/webp', WEBP)), '');
  });

  it('lets the bytes decide when the browser gives no useful type', async () => {
    assert.equal(await validateProfileImageContent(fileOf('a.png', '', PNG)), '');
    assert.equal(await validateProfileImageContent(fileOf('a.webp', 'application/octet-stream', WEBP)), '');
  });

  it('refuses a GIF, SVG or PDF renamed to an allowed extension, whatever type is declared', async () => {
    for (const [name, type] of [['a.png', 'image/png'], ['a.jpg', 'image/jpeg'], ['a.webp', 'image/webp'], ['a.jpeg', '']]) {
      assert.equal(await validateProfileImageContent(fileOf(name, type, GIF)), PROFILE_PICTURE_MESSAGES.notAnImage, name + ' GIF');
      assert.equal(await validateProfileImageContent(fileOf(name, type, text('<svg xmlns="http://www.w3.org/2000/svg"></svg>'))), PROFILE_PICTURE_MESSAGES.notAnImage, name + ' SVG');
      assert.equal(await validateProfileImageContent(fileOf(name, type, text('%PDF-1.7 ......'))), PROFILE_PICTURE_MESSAGES.notAnImage, name + ' PDF');
    }
  });

  it('refuses content that does not match the extension or the declared type', async () => {
    assert.equal(await validateProfileImageContent(fileOf('a.jpg', 'image/jpeg', PNG)), PROFILE_PICTURE_MESSAGES.mismatch);
    assert.equal(await validateProfileImageContent(fileOf('a.png', 'image/png', JPEG)), PROFILE_PICTURE_MESSAGES.mismatch);
    assert.equal(await validateProfileImageContent(fileOf('a.png', 'image/jpeg', PNG)), PROFILE_PICTURE_MESSAGES.mismatch);
  });

  it('reports an unreadable file instead of throwing', async () => {
    const problem = await validateProfileImageContent(fileOf('a.png', 'image/png', PNG), () => Promise.reject(new Error('gone')));
    assert.equal(problem, PROFILE_PICTURE_MESSAGES.unreadable);
  });
});

describe('name, type and size checks (before the file is read)', () => {
  it('accepts the three formats under the limit', () => {
    for (const [name, type] of [['a.jpg', 'image/jpeg'], ['a.jpeg', 'image/jpeg'], ['a.png', 'image/png'], ['a.webp', 'image/webp']]) {
      assert.equal(validateProfileImageFile(fileOf(name, type, JPEG)), '', name);
    }
  });

  it('refuses GIF, SVG, BMP, PDF and other formats by extension and by declared type', () => {
    for (const [name, type] of [['a.gif', 'image/gif'], ['a.svg', 'image/svg+xml'], ['a.bmp', 'image/bmp'], ['a.pdf', 'application/pdf'], ['a.tiff', 'image/tiff'], ['a', 'image/png'], ['a.png.exe', 'application/x-msdownload']]) {
      assert.equal(validateProfileImageFile(fileOf(name, type, PNG)), PROFILE_PICTURE_MESSAGES.unsupported, name);
    }
    assert.equal(validateProfileImageFile(fileOf('a.png', 'image/svg+xml', PNG)), PROFILE_PICTURE_MESSAGES.unsupported, 'allowed extension, unsupported declared type');
  });

  it('enforces exactly 5 MB with the agreed message', () => {
    assert.equal(PROFILE_IMAGE_MAX_BYTES, 5 * 1024 * 1024);
    assert.equal(PROFILE_PICTURE_MESSAGES.tooLarge, 'Image must be 5 MB or smaller.');
    assert.equal(validateProfileImageFile(fileOf('a.png', 'image/png', PNG, PROFILE_IMAGE_MAX_BYTES)), '');
    assert.equal(validateProfileImageFile(fileOf('a.png', 'image/png', PNG, PROFILE_IMAGE_MAX_BYTES + 1)), 'Image must be 5 MB or smaller.');
  });
});
