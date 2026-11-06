import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Cropper from 'react-easy-crop';
import { removeProfilePicture, uploadProfilePicture } from '../../services/api';
import {
  PROFILE_IMAGE_ACCEPT,
  PROFILE_PICTURE_MESSAGES,
  profilePictureErrorMessage,
  validateProfileImageContent,
  validateProfileImageFile,
} from '../../utils/profilePicture';
import { CROP_MAX_ZOOM, CROP_MIN_ZOOM, getCroppedFile, loadImage } from '../../utils/cropImage';

function Spinner({ className = 'h-4 w-4' }) {
  return (
    <svg className={`animate-spin ${className}`} fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

const Icon = ({ children, className = 'w-4 h-4' }) => (
  <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
);
const MouseIcon = (props) => (
  <Icon {...props}>
    <rect x="6" y="3" width="12" height="18" rx="6" />
    <path d="M12 7v4" />
  </Icon>
);
const ImageIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <path d="M21 15l-5-5L5 21" />
  </Icon>
);
const TrashIcon = (props) => (
  <Icon {...props}>
    <path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
  </Icon>
);
const FileIcon = (props) => (
  <Icon {...props}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 2v6h6" />
  </Icon>
);
const SizeIcon = (props) => (
  <Icon {...props}>
    <path d="M4 14h16M4 14l2 6h12l2-6M4 14l2-4h12l2 4M12 10V4" />
  </Icon>
);
const CircleIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="3" width="18" height="18" rx="2" strokeDasharray="3 3" />
    <circle cx="12" cy="12" r="6" />
  </Icon>
);

const SECONDARY_BUTTON = 'px-4 py-2 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';

/**
 * The profile picture editor: a modal with a square crop area (circular preview) over the current or a newly chosen picture.
 *
 * - Scroll wheel zooms (centred on the cursor), dragging moves the picture, touch drags and pinches; react-easy-crop does the
 *   gesture handling and keeps the picture covering the frame (zoom {CROP_MIN_ZOOM}-{CROP_MAX_ZOOM}).
 * - Save draws the selected area onto a 512 px canvas and uploads that file through the existing POST /api/profile/picture, so the
 *   original, uncropped file is never uploaded. Remove asks for confirmation, then calls the existing DELETE /api/profile/picture.
 * - Nothing changes until Save or Remove succeeds; Cancel, Escape and the backdrop just close it.
 *
 * onDone(updatedProfile, message) receives the profile the server answered with, so the page and the navbar update at once.
 */
export default function ProfilePictureEditor({ isOpen, savedImageUrl, onClose, onDone }) {
  const [source, setSource] = useState(null); // { url, local, file } of the picture in the crop area
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(CROP_MIN_ZOOM);
  const [pixels, setPixels] = useState(null);
  const [changed, setChanged] = useState(false); // the user chose another picture, or moved/zoomed this one
  const [phase, setPhase] = useState('idle'); // 'idle' | 'saving' | 'removing'
  const [error, setError] = useState('');
  const [confirmingRemove, setConfirmingRemove] = useState(false);
  const fileInputRef = useRef(null);
  const busy = phase !== 'idle';
  const busyRef = useRef(false);
  busyRef.current = busy;
  const inFlight = useRef(false); // set synchronously, so a double click cannot start two requests before the state updates
  const dialogRef = useRef(null);
  const selection = useRef(0); // the newest file choice wins if checks of an earlier one are still running

  // A fresh editor, showing the saved picture (if any), every time it opens
  useEffect(() => {
    if (!isOpen) return;
    setSource(savedImageUrl ? { url: savedImageUrl, local: false } : null);
    setCrop({ x: 0, y: 0 });
    setZoom(CROP_MIN_ZOOM);
    setPixels(null);
    setChanged(false);
    setPhase('idle');
    setError('');
    setConfirmingRemove(false);
    inFlight.current = false;
    dialogRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resets only when the dialog opens or closes, not when the saved image URL changes
  }, [isOpen]);

  // A chosen file's object URL is released when it is replaced or the editor closes
  useEffect(() => {
    const url = source?.local ? source.url : null;
    return () => {
      if (url) URL.revokeObjectURL(url);
    };
  }, [source]);

  // Escape closes (or dismisses the confirmation); the page behind does not scroll while the editor is open
  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || busyRef.current) return;
      if (confirmingRemove) setConfirmingRemove(false);
      else onClose();
    };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [isOpen, onClose, confirmingRemove]);

  const onCropComplete = useCallback((_area, areaPixels) => setPixels(areaPixels), []);

  if (!isOpen) return null;

  const handleFileChange = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = ''; // choosing the same file again still counts as a change
    if (!file) return;
    const choice = ++selection.current;
    // 1. name, declared type and size (5 MB) - before the file is read at all; 2. its real content (first bytes);
    // 3. it must decode as an image. Only then does it replace what is in the crop area, so a bad file never opens the cropper.
    let problem = validateProfileImageFile(file) || (await validateProfileImageContent(file));
    let url = null;
    if (!problem) {
      url = URL.createObjectURL(file);
      try {
        await loadImage(url);
      } catch {
        problem = PROFILE_PICTURE_MESSAGES.unreadable;
      }
    }
    if (choice !== selection.current) {
      if (url) URL.revokeObjectURL(url);
      return;
    }
    if (problem) {
      if (url) URL.revokeObjectURL(url);
      setError(problem);
      return;
    }
    setSource({ url, local: true, file });
    setCrop({ x: 0, y: 0 }); // a new picture starts centred at minimum zoom
    setZoom(CROP_MIN_ZOOM);
    setPixels(null);
    setChanged(true);
    setError('');
  };

  const handleSave = async () => {
    if (inFlight.current || busy || !source || !pixels || !changed) return;
    inFlight.current = true;
    setPhase('saving');
    setError('');
    let file;
    try {
      file = await getCroppedFile(source.url, pixels);
    } catch {
      setError(source.local ? PROFILE_PICTURE_MESSAGES.unreadable : 'Your current picture cannot be edited here. Choose a picture with Change Picture instead.');
      inFlight.current = false;
      setPhase('idle');
      return;
    }
    try {
      const updated = await uploadProfilePicture(file);
      inFlight.current = false;
      onDone(updated, PROFILE_PICTURE_MESSAGES.uploaded);
    } catch (err) {
      setError(profilePictureErrorMessage(err, PROFILE_PICTURE_MESSAGES.uploadFailed));
      inFlight.current = false;
      setPhase('idle');
    }
  };

  const handleRemove = async () => {
    if (inFlight.current || busy) return;
    inFlight.current = true;
    setPhase('removing');
    setError('');
    try {
      const updated = await removeProfilePicture();
      inFlight.current = false;
      onDone(updated, PROFILE_PICTURE_MESSAGES.removed);
    } catch (err) {
      setError(profilePictureErrorMessage(err, PROFILE_PICTURE_MESSAGES.removeFailed));
      inFlight.current = false;
      setConfirmingRemove(false);
      setPhase('idle');
    }
  };

  const dismiss = () => {
    if (!busy) onClose();
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs animate-fadeIn" onClick={dismiss}>
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="picture-editor-title"
        className="relative bg-white rounded-2xl flex flex-col w-full max-w-[800px] shadow-xl border border-gray-100 max-h-[85vh] overflow-hidden focus:outline-none"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="shrink-0 px-5 sm:px-6 py-5 border-b border-gray-200">
          <h3 id="picture-editor-title" className="text-lg font-bold text-gray-900 leading-tight">Edit Profile Picture</h3>
          <p className="text-sm text-gray-500 mt-1.5">Adjust how your picture appears across Taskotel.</p>
        </div>

        {/* Content: two columns from md up (crop area | actions). It only scrolls on short or narrow screens, so the footer stays in view. */}
        <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-5 sm:px-6 py-5 grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_17rem] gap-5 md:gap-6 items-start">
          {/* The crop area: a fixed square frame, the picture moves underneath. Its size follows the viewport height, so it always fits. */}
          <div
            className="relative [--crop:min(26rem,max(12rem,calc(85vh-230px)),calc(100vw-23.5rem))] max-md:[--crop:min(24rem,max(12rem,calc(85vh-340px)),calc(100vw-5.5rem))] w-[var(--crop)] h-[var(--crop)] mx-auto rounded-xl overflow-hidden bg-gray-100 border border-gray-200 touch-none overscroll-contain select-none"
            data-testid="crop-area"
          >
            {source ? (
              <Cropper
                image={source.url}
                crop={crop}
                zoom={zoom}
                minZoom={CROP_MIN_ZOOM}
                maxZoom={CROP_MAX_ZOOM}
                aspect={1}
                cropShape="round"
                showGrid={false}
                objectFit="cover"
                zoomWithScroll
                zoomSpeed={0.25}
                style={{ cropAreaStyle: { border: '2px solid rgba(255, 255, 255, 0.95)', color: 'rgba(17, 24, 39, 0.55)' } }}
                mediaProps={source.local ? undefined : { crossOrigin: 'anonymous' }}
                onCropChange={setCrop}
                onZoomChange={setZoom}
                onCropComplete={onCropComplete}
                onInteractionStart={() => setChanged(true)}
              />
            ) : (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-center px-6">
                <div className="w-28 h-28 rounded-full border-2 border-dashed border-gray-300 flex items-center justify-center text-gray-500 text-sm">No picture</div>
                <p className="text-sm text-gray-500">You have no profile picture yet. Choose one to get started.</p>
              </div>
            )}
          </div>

          {/* Actions and information */}
          <div className="flex flex-col justify-center gap-4 min-w-0 md:self-stretch">
            {/* Helper text, not a control */}
            <p className="flex items-center gap-2 text-xs font-medium leading-4 text-gray-600">
              <MouseIcon className="w-4 h-4 shrink-0 text-gray-500" />
              Scroll to zoom · Drag to reposition
            </p>

            <section aria-labelledby="picture-actions-title" className="space-y-2">
              <h4 id="picture-actions-title" className="text-xs font-semibold uppercase tracking-wide text-gray-500">Image Actions</h4>
              <button
                type="button"
                onClick={() => !busy && fileInputRef.current?.click()}
                disabled={busy}
                className="w-full h-10 px-4 flex items-center justify-center gap-2 text-emerald-700 text-sm font-semibold rounded-xl border border-emerald-300 bg-emerald-50 hover:bg-emerald-100 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
              >
                <ImageIcon className="w-4 h-4" />
                Change Picture
              </button>
              {savedImageUrl && (
                <button
                  type="button"
                  onClick={() => setConfirmingRemove(true)}
                  disabled={busy}
                  className="w-full h-8 px-3 flex items-center justify-center gap-1.5 text-red-600 text-xs font-medium rounded-lg hover:bg-red-50 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
                >
                  <TrashIcon className="w-3.5 h-3.5" />
                  Remove Picture
                </button>
              )}
              <input ref={fileInputRef} type="file" accept={PROFILE_IMAGE_ACCEPT} className="hidden" onChange={handleFileChange} aria-label="Choose a profile picture" />
            </section>

            {/* Requirements: compact single-line rows, no card */}
            <section aria-labelledby="picture-requirements-title" className="space-y-2">
              <h4 id="picture-requirements-title" className="text-xs font-semibold uppercase tracking-wide text-gray-500">Image Requirements</h4>
              <ul aria-label="Image requirements" className="space-y-1.5 text-xs text-gray-500">
              <li className="flex items-center gap-2 whitespace-nowrap">
                <FileIcon className="w-3.5 h-3.5 shrink-0 text-gray-400" />
                JPG, JPEG, PNG, WebP
              </li>
              <li className="flex items-center gap-2 whitespace-nowrap">
                <SizeIcon className="w-3.5 h-3.5 shrink-0 text-gray-400" />
                Maximum size: 5 MB
              </li>
              <li className="flex items-center gap-2 whitespace-nowrap">
                <CircleIcon className="w-3.5 h-3.5 shrink-0 text-gray-400" />
                Square crop · Circular avatar
              </li>
              </ul>
            </section>

            {error && <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</p>}
          </div>
        </div>

        {/* Footer: always visible, outside the scrolling content */}
        <div className="shrink-0 flex items-center justify-end gap-3 px-5 sm:px-6 py-4 border-t border-gray-200 bg-gray-50">
          <button type="button" onClick={dismiss} disabled={busy} className={`${SECONDARY_BUTTON} h-10`}>Cancel</button>
          <button
            type="button"
            onClick={handleSave}
            disabled={busy || !source || !pixels || !changed}
            className="h-10 px-5 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1"
          >
            {phase === 'saving' ? (<><Spinner />Saving…</>) : 'Save Picture'}
          </button>
        </div>

        {confirmingRemove && (
          <div className="absolute inset-0 z-10 flex items-center justify-center p-5 bg-gray-900/50 rounded-2xl" onClick={() => !busy && setConfirmingRemove(false)}>
            <div
              role="alertdialog"
              aria-modal="true"
              aria-labelledby="picture-remove-title"
              aria-describedby="picture-remove-text"
              className="bg-white rounded-xl border border-gray-200 shadow-xl p-5 w-full max-w-xs space-y-3"
              onClick={(event) => event.stopPropagation()}
            >
              <h4 id="picture-remove-title" className="text-base font-bold text-gray-900">Remove profile picture?</h4>
              <p id="picture-remove-text" className="text-sm text-gray-500">Your default avatar will be shown instead. You can add a picture again at any time.</p>
              <div className="flex items-center justify-end gap-2 pt-1">
                <button type="button" onClick={() => setConfirmingRemove(false)} disabled={busy} className={SECONDARY_BUTTON}>Keep picture</button>
                <button
                  type="button"
                  onClick={handleRemove}
                  disabled={busy}
                  className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-70 disabled:cursor-wait transition-colors"
                >
                  {phase === 'removing' ? (<><Spinner />Removing…</>) : 'Remove'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}
