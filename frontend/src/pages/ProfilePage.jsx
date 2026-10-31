import React, { useEffect, useReducer, useRef } from 'react';
import { getUsername, getUserRole, uploadProfilePicture, removeProfilePicture } from '../services/api';
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

  // Each local preview URL is released as soon as it is replaced, cancelled, saved or the page closes
  useEffect(() => {
    const url = picture.previewUrl;
    return () => {
      if (url) URL.revokeObjectURL(url);
    };
  }, [picture.previewUrl]);

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

  return (
    <div className="w-full max-w-5xl space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">User Profile</h1>
        <p className="text-gray-500 mt-1">Manage your account credentials, personal details, and preferences.</p>
      </div>

      {/* Main Profile Card */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-6 sm:p-8 space-y-6">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-5 pb-6 border-b border-gray-100">
          {/* Profile picture: the saved one, the default avatar, or the preview of a newly chosen file */}
          <div className="relative w-20 h-20 shrink-0">
            {previewing ? (
              <img
                src={picture.previewUrl}
                alt="Preview of your new profile picture"
                className="w-20 h-20 rounded-2xl object-cover ring-2 ring-emerald-500 ring-offset-2"
                onError={() => dispatch({ type: 'previewFailed' })}
              />
            ) : (
              <UserAvatar
                imageUrl={savedImageUrl}
                initials={username.slice(0, 2).toUpperCase()}
                name={username}
                className="w-20 h-20 rounded-2xl shadow-md shadow-emerald-700/20"
                fallbackClassName="bg-gradient-to-tr from-emerald-600 via-emerald-500 to-teal-400 text-white text-2xl font-bold"
              />
            )}
            {busy && (
              <div className="absolute inset-0 rounded-2xl bg-black/40 flex items-center justify-center">
                <Spinner className="h-6 w-6 text-white" />
              </div>
            )}
          </div>

          <div className="space-y-1 min-w-0">
            <div className="flex items-center gap-3">
              <h2 className="text-xl font-bold text-gray-900">{username}</h2>
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800">
                {role}
              </span>
            </div>
            <p className="text-sm text-gray-500">Taskotel — Hotel Operations, Simplified</p>
            <p className="text-xs text-gray-400 font-mono">ID: @{username}</p>

            {/* Profile picture actions */}
            <div className="flex flex-wrap items-center gap-2 pt-3">
              {previewing ? (
                <>
                  <button
                    type="button"
                    onClick={handleSave}
                    disabled={busy}
                    className={`px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-medium rounded-lg transition-colors flex items-center gap-2 cursor-pointer shadow-xs ${
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
                    className="px-4 py-2 text-gray-700 text-sm font-medium hover:bg-gray-50 rounded-lg transition-colors border border-gray-200 cursor-pointer disabled:opacity-50"
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
                    className="px-4 py-2 text-emerald-700 text-sm font-medium hover:bg-emerald-50 rounded-lg transition-colors border border-emerald-200 cursor-pointer disabled:opacity-50"
                  >
                    Change Profile Picture
                  </button>
                  {savedImageUrl && (
                    <button
                      type="button"
                      onClick={handleRemove}
                      disabled={busy}
                      className="px-4 py-2 text-red-600 text-sm font-medium hover:bg-red-50 rounded-lg transition-colors border border-transparent cursor-pointer disabled:opacity-50 flex items-center gap-2"
                    >
                      {removing ? (
                        <>
                          <Spinner className="h-4 w-4 text-red-600" />
                          Removing…
                        </>
                      ) : (
                        'Remove picture'
                      )}
                    </button>
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
            <p className="text-xs text-gray-400">
              {previewing ? 'Preview — not saved yet. Save to use this picture, or cancel to keep your current one.' : 'JPG, PNG or WebP, up to 5 MB. Pictures are cropped to a square.'}
            </p>
            {picture.error && (
              <p role="alert" className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                {picture.error}
              </p>
            )}
            {picture.success && (
              <p role="status" aria-live="polite" className="text-sm text-emerald-800 bg-emerald-50 border border-emerald-100 rounded-lg px-3 py-2">
                {picture.success}
              </p>
            )}
          </div>
        </div>

        {/* Profile Details Form / Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Username</label>
            <input
              type="text"
              readOnly
              value={username}
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">System Role</label>
            <input
              type="text"
              readOnly
              value={role}
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</label>
            <div className="flex items-center gap-2 px-4 py-2.5 bg-emerald-50/50 border border-emerald-100 rounded-xl text-sm font-medium text-emerald-800">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span>Active & Authenticated</span>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Access Scope</label>
            <input
              type="text"
              readOnly
              value={role === 'ADMIN' ? 'Full Administrator Access' : role === 'MANAGER' ? 'Operations & Management Access' : 'Standard Staff Access'}
              className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 rounded-xl text-sm font-medium text-gray-800 cursor-not-allowed"
            />
          </div>
        </div>
      </div>
    </div>
  );
}
