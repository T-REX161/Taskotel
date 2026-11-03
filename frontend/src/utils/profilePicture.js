/**
 * Profile picture rules and the state of the "change picture" flow, kept free of React and the DOM so they can be tested.
 * The backend checks everything again (including the file's real content); these checks only give quicker feedback.
 */

export const PROFILE_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const PROFILE_IMAGE_ACCEPT = '.jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp';

const EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp'];
const TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_MESSAGE_LENGTH = 200;

export const PROFILE_PICTURE_MESSAGES = {
  missing: 'Choose an image to upload.',
  empty: 'The selected file is empty. Please choose another image.',
  tooLarge: 'Image must be 5 MB or smaller.',
  unsupported: 'Only JPG, JPEG, PNG or WebP images can be uploaded.',
  unreadable: 'This file could not be read as an image. Please choose another one.',
  notAnImage: 'The file is not a valid JPG, PNG or WebP image.',
  mismatch: "The file's content does not match its type. Please choose a real JPG, PNG or WebP image.",
  uploadFailed: 'Your picture could not be uploaded. Please try again.',
  removeFailed: 'Your picture could not be removed. Please try again.',
  network: 'Network error. Please check your connection and try again.',
  uploaded: 'Profile picture updated successfully.',
  removed: 'Profile picture removed.',
};

/** The problem with a chosen file, or '' when it can be previewed and uploaded. */
export function validateProfileImageFile(file) {
  if (!file) return PROFILE_PICTURE_MESSAGES.missing;
  const name = String(file.name || '').trim().toLowerCase();
  const dot = name.lastIndexOf('.');
  const extension = dot < 0 ? '' : name.slice(dot + 1);
  if (!EXTENSIONS.includes(extension)) return PROFILE_PICTURE_MESSAGES.unsupported;
  const type = String(file.type || '').toLowerCase();
  if (type && !TYPES.includes(type === 'image/jpg' ? 'image/jpeg' : type)) return PROFILE_PICTURE_MESSAGES.unsupported;
  if (!(file.size > 0)) return PROFILE_PICTURE_MESSAGES.empty;
  if (file.size > PROFILE_IMAGE_MAX_BYTES) return PROFILE_PICTURE_MESSAGES.tooLarge;
  return '';
}

/** The image kind ('jpeg', 'png' or 'webp') from a file's first bytes, or null when they are none of the allowed kinds. */
export function sniffImageType(bytes) {
  const b = bytes || [];
  const at = (i, ...sig) => b.length >= i + sig.length && sig.every((v, k) => b[i + k] === v);
  if (at(0, 0xff, 0xd8, 0xff)) return 'jpeg';
  if (at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return 'png';
  if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) return 'webp';
  return null;
}

const EXTENSION_KIND = { jpg: 'jpeg', jpeg: 'jpeg', png: 'png', webp: 'webp' };
const TYPE_KIND = { 'image/jpeg': 'jpeg', 'image/jpg': 'jpeg', 'image/pjpeg': 'jpeg', 'image/png': 'png', 'image/webp': 'webp' };

/**
 * The problem with a file's real content, or '' when its first bytes are a JPEG, PNG or WebP that agrees with its extension and with
 * the type the browser declared. A renamed GIF, SVG or PDF is caught here, whatever its name says. Reads only the first 12 bytes.
 */
export async function validateProfileImageContent(file, readHead = (f) => f.slice(0, 12).arrayBuffer()) {
  let head;
  try {
    head = new Uint8Array(await readHead(file));
  } catch {
    return PROFILE_PICTURE_MESSAGES.unreadable;
  }
  const actual = sniffImageType(head);
  if (!actual) return PROFILE_PICTURE_MESSAGES.notAnImage;
  const name = String(file.name || '').trim().toLowerCase();
  const byExtension = EXTENSION_KIND[name.slice(name.lastIndexOf('.') + 1)];
  const declared = String(file.type || '').toLowerCase();
  const byType = declared && declared !== 'application/octet-stream' ? TYPE_KIND[declared] : actual; // an unknown declared type: the bytes decide
  if (actual !== byExtension || actual !== byType) return PROFILE_PICTURE_MESSAGES.mismatch;
  return '';
}

/**
 * phase: 'idle' (nothing chosen), 'selected' (a valid file is previewed, waiting for Save or Cancel),
 * 'uploading', or 'removing'. previewUrl is a local object URL for the chosen file; nothing leaves the browser before Save.
 */
export const initialProfilePictureState = Object.freeze({ file: null, previewUrl: null, phase: 'idle', error: '', success: '' });

export function profilePictureReducer(state, action) {
  switch (action.type) {
    case 'select':
      return { ...initialProfilePictureState, file: action.file, previewUrl: action.previewUrl, phase: 'selected' };
    case 'invalid':
      return { ...initialProfilePictureState, error: action.error };
    case 'previewFailed':
      return { ...initialProfilePictureState, error: PROFILE_PICTURE_MESSAGES.unreadable };
    case 'cancel':
      return state.phase === 'uploading' || state.phase === 'removing' ? state : { ...initialProfilePictureState };
    case 'uploadStart':
      return state.phase === 'selected' ? { ...state, phase: 'uploading', error: '', success: '' } : state;
    case 'uploadSuccess':
      return { ...initialProfilePictureState, success: PROFILE_PICTURE_MESSAGES.uploaded };
    case 'uploadFailure':
      // The chosen file stays previewed, so it can be saved again or cancelled; the saved picture is unchanged
      return { ...state, phase: 'selected', error: action.error, success: '' };
    case 'removeStart':
      return state.phase === 'idle' ? { ...state, phase: 'removing', error: '', success: '' } : state;
    case 'removeSuccess':
      return { ...initialProfilePictureState, success: PROFILE_PICTURE_MESSAGES.removed };
    case 'removeFailure':
      return { ...initialProfilePictureState, error: action.error };
    case 'dismiss':
      return { ...state, error: '', success: '' };
    default:
      return state;
  }
}

/** What the avatar shows: the local preview while a new picture is chosen or being saved, otherwise the saved picture. */
export function displayedProfileImage(state, savedUrl) {
  if (state.previewUrl && (state.phase === 'selected' || state.phase === 'uploading')) return state.previewUrl;
  return savedUrl || null;
}

/**
 * The message for a failed upload or removal. The backend's own message is shown when it is a short plain string (its 400,
 * 413, 415, 502 and 503 answers are written for people); a request that never got an answer gets the network message.
 */
export function profilePictureErrorMessage(error, fallback) {
  if (!error) return fallback;
  if (!error.status) return PROFILE_PICTURE_MESSAGES.network;
  const message = typeof error.message === 'string' ? error.message.trim() : '';
  return message && message.length <= MAX_MESSAGE_LENGTH ? message : fallback;
}

/** Two-letter initials for the default avatar: "Dua Dino" -> "DD", "admin" -> "AD". */
export function avatarInitials(name, fallback = '?') {
  const text = String(name || '').trim();
  if (!text) return fallback;
  const parts = text.split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  return text.slice(0, 2).toUpperCase();
}
