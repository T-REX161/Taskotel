import React, { useEffect, useReducer, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { getUsername, getUserRole, uploadProfilePicture, removeProfilePicture, updateDisplayName } from '../services/api';
import { DISPLAY_NAME_MAX, DISPLAY_NAME_MESSAGES, shownName, validateDisplayName } from '../utils/displayName';
import useCurrentUserProfile from '../hooks/useCurrentUserProfile';
import { setCurrentProfile } from '../utils/currentUserProfile';
import UserAvatar from '../components/common/UserAvatar';
import {
  PROFILE_IMAGE_ACCEPT,
  PROFILE_PICTURE_MESSAGES,
  initialProfilePictureState,
  profilePictureErrorMessage,
  profilePictureReducer,
  validateProfileImageFile,
} from '../utils/profilePicture';

function Spinner({ className = 'h-4 w-4 text-white' }) {
  return (
    <svg className={`animate-spin ${className}`} fill="none" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  );
}

function Icon({ className = 'w-5 h-5', children }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}

const CameraIcon = (props) => (
  <Icon {...props}>
    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
    <circle cx="12" cy="13" r="4" />
  </Icon>
);
const DotsIcon = (props) => (
  <Icon {...props}>
    <circle cx="5" cy="12" r="1" />
    <circle cx="12" cy="12" r="1" />
    <circle cx="19" cy="12" r="1" />
  </Icon>
);
const TrashSmallIcon = (props) => (
  <Icon {...props}>
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    <path d="M10 11v6M14 11v6" />
    <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
  </Icon>
);
const UserOutlineIcon = (props) => (
  <Icon {...props}>
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </Icon>
);
const ShieldIcon = (props) => (
  <Icon {...props}>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
  </Icon>
);
const PencilIcon = (props) => (
  <Icon {...props}>
    <path d="M12 20h9" />
    <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z" />
  </Icon>
);
const TagIcon = (props) => (
  <Icon {...props}>
    <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" />
    <line x1="7" y1="7" x2="7.01" y2="7" />
  </Icon>
);
const GridIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="3" width="7" height="7" rx="1" />
    <rect x="14" y="3" width="7" height="7" rx="1" />
    <rect x="3" y="14" width="7" height="7" rx="1" />
    <rect x="14" y="14" width="7" height="7" rx="1" />
  </Icon>
);
const HomeIcon = (props) => (
  <Icon {...props}>
    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    <polyline points="9 22 9 12 15 12 15 22" />
  </Icon>
);

// A yes/no account flag from the backend: green when true, red when false, neutral while the profile has not loaded
const flagState = (value) => (value === true ? 'on' : value === false ? 'off' : 'unknown');
const flagDot = (value) => ({ on: 'bg-emerald-500', off: 'bg-red-500', unknown: 'bg-gray-400' })[flagState(value)];
const flagText = (value, yes, no) => ({ on: yes, off: no, unknown: 'Loading…' })[flagState(value)];
const flagField = (value) => ({ tone: flagState(value) });

function DetailField({ label, icon: FieldIcon, children, tone = 'plain' }) {
  const highlight = tone === 'on';
  const toneClass = {
    on: 'bg-emerald-50 border-emerald-200 text-emerald-800',
    off: 'bg-red-50 border-red-200 text-red-800',
    unknown: 'bg-gray-50 border-gray-200 text-gray-500',
    plain: 'bg-gray-50 border-gray-200 text-gray-800',
  }[tone];
  return (
    <div className="space-y-2.5 min-w-0">
      <p className="text-sm font-semibold text-gray-700">{label}</p>
      <div
        className={`flex items-center gap-3 px-4 h-12 rounded-xl border text-sm font-medium min-w-0 ${toneClass}`}
      >
        {FieldIcon && <FieldIcon className={`w-4 h-4 shrink-0 ${highlight ? 'text-emerald-600' : 'text-gray-500'}`} />}
        <span className="flex items-center gap-2.5 min-w-0 truncate">{children}</span>
      </div>
    </div>
  );
}

export default function ProfilePage() {
  const username = getUsername() || 'admin';
  const role = getUserRole() || 'ADMIN';
  const { profile } = useCurrentUserProfile();
  const savedImageUrl = profile?.profileImageUrl || null;

  const [picture, dispatch] = useReducer(profilePictureReducer, initialProfilePictureState);
  const fileInputRef = useRef(null);
  const previewing = picture.phase === 'selected' || picture.phase === 'uploading';
  const uploading = picture.phase === 'uploading';
  const removing = picture.phase === 'removing';
  const busy = uploading || removing;
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  // Display name editing: `editingName` shows the input, `savingName` blocks a second submit while the request runs
  const displayName = shownName(profile, username);
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState('');
  const [savingName, setSavingName] = useState(false);
  const [nameError, setNameError] = useState('');
  const [nameSaved, setNameSaved] = useState(false);
  const nameInputRef = useRef(null);

  useEffect(() => {
    if (editingName) nameInputRef.current?.focus();
  }, [editingName]);

  const startEditingName = () => {
    setNameDraft(displayName);
    setNameError('');
    setNameSaved(false);
    setEditingName(true);
  };

  const cancelEditingName = () => {
    if (savingName) return;
    setEditingName(false);
    setNameError('');
  };

  const handleNameSubmit = async (event) => {
    event.preventDefault();
    if (savingName) return;
    const problem = validateDisplayName(nameDraft);
    if (problem) {
      setNameError(problem);
      return;
    }
    setSavingName(true);
    setNameError('');
    try {
      const updated = await updateDisplayName(nameDraft.trim());
      setCurrentProfile(updated); // the banner and the top navigation read the shared profile, so both change at once
      setEditingName(false);
      setNameSaved(true);
    } catch (error) {
      setNameError(error?.status === 400 && error.message ? error.message : DISPLAY_NAME_MESSAGES.saveFailed);
    } finally {
      setSavingName(false);
    }
  };

  // Each local preview URL is released as soon as it is replaced, cancelled, saved or the page closes
  useEffect(() => {
    const url = picture.previewUrl;
    return () => {
      if (url) URL.revokeObjectURL(url);
    };
  }, [picture.previewUrl]);

  // The picture actions menu closes on an outside click or Escape
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onPointerDown = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false);
    };
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menuOpen]);

  const chooseFile = () => {
    if (!busy) fileInputRef.current?.click();
  };

  const handleFileChange = (event) => {
    const file = event.target.files?.[0];
    event.target.value = ''; // choosing the same file again still counts as a change
    if (!file) return;
    const problem = validateProfileImageFile(file);
    if (problem) {
      dispatch({ type: 'invalid', error: problem });
      return;
    }
    dispatch({ type: 'select', file, previewUrl: URL.createObjectURL(file) });
  };

  const handleSave = async () => {
    if (picture.phase !== 'selected' || !picture.file) return;
    dispatch({ type: 'uploadStart' });
    try {
      const updated = await uploadProfilePicture(picture.file);
      setCurrentProfile(updated);
      dispatch({ type: 'uploadSuccess' });
    } catch (error) {
      dispatch({ type: 'uploadFailure', error: profilePictureErrorMessage(error, PROFILE_PICTURE_MESSAGES.uploadFailed) });
    }
  };

  const handleRemove = async () => {
    setMenuOpen(false);
    if (picture.phase !== 'idle' || !savedImageUrl) return;
    dispatch({ type: 'removeStart' });
    try {
      const updated = await removeProfilePicture();
      setCurrentProfile(updated);
      dispatch({ type: 'removeSuccess' });
    } catch (error) {
      dispatch({ type: 'removeFailure', error: profilePictureErrorMessage(error, PROFILE_PICTURE_MESSAGES.removeFailed) });
    }
  };

  const accessScope = role === 'ADMIN' ? 'Full Administrator Access' : role === 'MANAGER' ? 'Operations & Management Access' : 'Standard Staff Access';

  return (
    <div className="w-full max-w-6xl space-y-6">
      {/* Page heading */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">User Profile</h1>
          <p className="text-gray-500 mt-2">Manage your account credentials, personal details, and preferences.</p>
        </div>
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-sm text-gray-500 shrink-0">
          <Link to="/dashboard" className="hover:text-emerald-600 transition-colors" aria-label="Dashboard">
            <HomeIcon className="w-4 h-4" />
          </Link>
          <span aria-hidden="true">/</span>
          <span className="font-medium text-gray-800">Profile</span>
        </nav>
      </div>

      {/* Profile banner */}
      <section className="relative rounded-2xl border border-emerald-200 dark:border-emerald-500/20 shadow-sm bg-gradient-to-br from-emerald-50 via-white to-white dark:from-emerald-900 dark:via-emerald-950 dark:to-black">
        <div className="absolute inset-0 rounded-2xl overflow-hidden pointer-events-none" aria-hidden="true">
          <div className="absolute -left-16 -top-24 w-80 h-80 rounded-full bg-emerald-400/10 dark:bg-emerald-500/10 blur-3xl" />
        </div>

        <div className="relative flex flex-col md:flex-row items-center gap-6 md:gap-10 p-6 sm:p-8 lg:px-12 lg:py-10">
          {/* Profile picture: the saved one, the default avatar, or the preview of a newly chosen file */}
          <div className="relative w-36 h-36 lg:w-44 lg:h-44 shrink-0">
            {previewing ? (
              <img
                src={picture.previewUrl}
                alt="Preview of your new profile picture"
                className="w-full h-full rounded-full object-cover border-4 border-emerald-500 shadow-lg shadow-emerald-900/20"
                onError={() => dispatch({ type: 'previewFailed' })}
              />
            ) : (
              <UserAvatar
                imageUrl={savedImageUrl}
                initials={username.slice(0, 2).toUpperCase()}
                name={username}
                className="w-full h-full rounded-full border-4 border-emerald-500 shadow-lg shadow-emerald-900/20"
                fallbackClassName="bg-gradient-to-tr from-emerald-600 via-emerald-500 to-teal-400 text-white text-5xl font-bold"
              />
            )}
            {busy && (
              <div className="absolute inset-0 rounded-full bg-black/40 flex items-center justify-center">
                <Spinner className="h-8 w-8 text-white" />
              </div>
            )}
            {!previewing && (
              <button
                type="button"
                onClick={chooseFile}
                disabled={busy}
                className="absolute bottom-2 right-2 w-10 h-10 rounded-full bg-emerald-500 hover:bg-emerald-600 text-white flex items-center justify-center shadow-md border-2 border-white cursor-pointer disabled:opacity-50 transition-colors"
                aria-label="Choose a new profile picture"
              >
                <CameraIcon className="w-5 h-5" />
              </button>
            )}
          </div>

          <div className="flex-1 min-w-0 w-full flex flex-col lg:flex-row lg:items-center gap-6">
            <div className="flex-1 min-w-0 space-y-2 text-center md:text-left">
              <div className="flex flex-wrap items-center justify-center md:justify-start gap-3">
                <h2 className="text-3xl lg:text-4xl font-bold leading-tight text-gray-900 break-words min-w-0">{displayName}</h2>
                <span className="inline-flex items-center px-3 py-1 rounded-lg text-xs font-bold tracking-wide bg-emerald-100 text-emerald-800 border border-emerald-200">
                  {role}
                </span>
              </div>
              <p className="text-base text-gray-600">Taskotel — Hotel Operations, Simplified</p>
              <p className="flex items-center justify-center md:justify-start gap-2 text-sm text-gray-500">
                <span className="px-1.5 py-0.5 rounded bg-gray-100 border border-gray-200 text-xs font-semibold text-gray-600">ID</span>
                <span className="font-mono">@{username}</span>
              </p>
            </div>

            {/* Profile picture actions */}
            <div className="flex flex-col items-center lg:items-end gap-3 shrink-0">
              <div className="flex flex-wrap items-center justify-center gap-2">
                {previewing ? (
                  <>
                    <button
                      type="button"
                      onClick={handleSave}
                      disabled={busy}
                      className={`px-5 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-semibold rounded-xl transition-colors flex items-center gap-2 cursor-pointer shadow-xs ${
                        uploading ? 'opacity-80 cursor-wait' : ''
                      }`}
                    >
                      {uploading ? (
                        <>
                          <Spinner />
                          Uploading…
                        </>
                      ) : (
                        'Save picture'
                      )}
                    </button>
                    <button
                      type="button"
                      onClick={() => dispatch({ type: 'cancel' })}
                      disabled={busy}
                      className="px-5 py-2.5 text-gray-700 text-sm font-semibold hover:bg-gray-50 rounded-xl transition-colors border border-gray-200 bg-white cursor-pointer disabled:opacity-50"
                    >
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      type="button"
                      onClick={chooseFile}
                      disabled={busy}
                      className="px-5 py-2.5 text-emerald-700 text-sm font-semibold bg-emerald-50 hover:bg-emerald-100 rounded-xl transition-colors border border-emerald-300 cursor-pointer disabled:opacity-50 flex items-center gap-2"
                    >
                      <CameraIcon className="w-4 h-4" />
                      Change Profile Picture
                    </button>
                    {savedImageUrl && (
                      <div className="relative" ref={menuRef}>
                        <button
                          type="button"
                          onClick={() => setMenuOpen((open) => !open)}
                          disabled={busy}
                          className="w-10 h-10 rounded-xl border border-gray-200 bg-white hover:bg-gray-50 text-gray-600 flex items-center justify-center cursor-pointer disabled:opacity-50 transition-colors"
                          aria-label="More picture actions"
                          aria-haspopup="menu"
                          aria-expanded={menuOpen}
                        >
                          {removing ? <Spinner className="h-4 w-4 text-red-600" /> : <DotsIcon className="w-5 h-5" />}
                        </button>
                        {menuOpen && (
                          <div role="menu" className="absolute right-0 top-12 z-20 w-48 rounded-xl bg-white border border-gray-200 shadow-lg py-1 animate-fadeIn">
                            <button
                              type="button"
                              role="menuitem"
                              onClick={handleRemove}
                              className="w-full text-left px-3.5 py-2 text-sm font-medium text-red-600 hover:bg-red-50 flex items-center gap-2.5 cursor-pointer"
                            >
                              <TrashSmallIcon className="w-4 h-4" />
                              Remove picture
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </>
                )}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept={PROFILE_IMAGE_ACCEPT}
                  className="hidden"
                  onChange={handleFileChange}
                  aria-label="Choose a profile picture"
                />
              </div>
              <p className="text-xs text-gray-500 text-center lg:text-right lg:whitespace-nowrap">
                {previewing ? 'Preview — not saved yet. Save to use this picture, or cancel to keep your current one.' : 'JPG, PNG or WebP, up to 5 MB. Pictures are cropped to a square.'}
              </p>
            </div>
          </div>
        </div>

        {(picture.error || picture.success) && (
          <div className="relative px-6 sm:px-10 pb-6 -mt-2">
            {picture.error && (
              <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                {picture.error}
              </p>
            )}
            {picture.success && (
              <p role="status" aria-live="polite" className="text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                {picture.success}
              </p>
            )}
          </div>
        )}
      </section>

      {/* Personal Information */}
      <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        <div className="flex items-center gap-4 px-6 sm:px-8 py-6 border-b border-gray-100">
          <UserOutlineIcon className="w-7 h-7 text-gray-500 shrink-0" />
          <div className="min-w-0">
            <h2 className="text-lg font-bold text-gray-900">Personal Information</h2>
            <p className="text-sm text-gray-500 mt-0.5">Basic information about your account.</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-7 px-6 sm:px-8 py-7">
          <div className="space-y-2.5 min-w-0">
            <div className="flex items-center justify-between gap-3 h-5">
              <label htmlFor="display-name" className="text-sm font-semibold text-gray-700">Display Name</label>
              {!editingName && (
                <button
                  type="button"
                  onClick={startEditingName}
                  className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700 hover:text-emerald-800 cursor-pointer"
                >
                  <PencilIcon className="w-3.5 h-3.5" />
                  Edit
                </button>
              )}
            </div>
            {editingName ? (
              <form onSubmit={handleNameSubmit} noValidate className="space-y-3">
                <input
                  id="display-name"
                  ref={nameInputRef}
                  type="text"
                  value={nameDraft}
                  maxLength={DISPLAY_NAME_MAX}
                  disabled={savingName}
                  onChange={(event) => {
                    setNameDraft(event.target.value);
                    if (nameError) setNameError('');
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Escape') cancelEditingName();
                  }}
                  aria-invalid={nameError ? 'true' : 'false'}
                  aria-describedby={nameError ? 'display-name-error' : undefined}
                  className={`w-full h-12 px-4 rounded-xl border text-sm font-medium text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500 disabled:opacity-60 ${
                    nameError ? 'border-red-400' : 'border-gray-300'
                  }`}
                />
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2">
                    <button
                      type="submit"
                      disabled={savingName}
                      className={`px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-80 disabled:cursor-wait`}
                    >
                      {savingName ? (
                        <>
                          <Spinner />
                          Saving…
                        </>
                      ) : (
                        'Save'
                      )}
                    </button>
                    <button
                      type="button"
                      onClick={cancelEditingName}
                      disabled={savingName}
                      className="px-4 py-2 text-gray-700 text-sm font-semibold rounded-xl border border-gray-200 bg-white hover:bg-gray-50 cursor-pointer disabled:opacity-50"
                    >
                      Cancel
                    </button>
                  </div>
                  <span className="text-xs text-gray-500 tabular-nums">{[...nameDraft].length}/{DISPLAY_NAME_MAX}</span>
                </div>
                {nameError && (
                  <p id="display-name-error" role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                    {nameError}
                  </p>
                )}
              </form>
            ) : (
              <>
                <div className="flex items-center gap-3 px-4 h-12 rounded-xl border text-sm font-medium min-w-0 bg-gray-50 border-gray-200 text-gray-800">
                  <TagIcon className="w-4 h-4 shrink-0 text-gray-500" />
                  <span id="display-name" className="truncate" title={displayName}>{displayName}</span>
                </div>
                {nameSaved && (
                  <p role="status" aria-live="polite" className="text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                    {DISPLAY_NAME_MESSAGES.saved}
                  </p>
                )}
              </>
            )}
          </div>
          <DetailField label="Username" icon={UserOutlineIcon}>{username}</DetailField>
          <DetailField label="Status" {...flagField(profile?.active)}>
            <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${flagDot(profile?.active)}`} />
            <span>{flagText(profile?.active, 'Active', 'Inactive')}</span>
          </DetailField>
          {/* Email verification from the account itself, not whether this browser is signed in */}
          <DetailField label="Email Verified" {...flagField(profile?.emailVerified)}>
            <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${flagDot(profile?.emailVerified)}`} />
            <span>{flagText(profile?.emailVerified, 'Yes', 'No')}</span>
          </DetailField>
          <DetailField label="System Role" icon={ShieldIcon}>{role}</DetailField>
          <DetailField label="Access Scope" icon={GridIcon}>{accessScope}</DetailField>
        </div>
      </section>
    </div>
  );
}
