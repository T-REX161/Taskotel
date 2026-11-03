import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { getUsername, getUserRole, updateDisplayName } from '../services/api';
import { DISPLAY_NAME_MAX, DISPLAY_NAME_MESSAGES, shownName, validateDisplayName } from '../utils/displayName';
import useCurrentUserProfile from '../hooks/useCurrentUserProfile';
import useTransientNotice from '../hooks/useTransientNotice';
import { setCurrentProfile } from '../utils/currentUserProfile';
import UserAvatar from '../components/common/UserAvatar';
import EmailChangeDialog from '../components/profile/EmailChangeDialog';
import PhoneChangeDialog from '../components/profile/PhoneChangeDialog';
import { PHONE_MESSAGES } from '../utils/phoneNumber';
import { EMAIL_CHANGE_MESSAGES } from '../utils/emailChange';
import { describeActivity } from '../utils/accountActivity';
import { accessScopeFor, accountStatus, emailAddress, emailVerification, phoneNumber } from '../utils/profileView';
import ProfilePictureEditor from '../components/profile/ProfilePictureEditor';

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
const UserOutlineIcon = (props) => (
  <Icon {...props}>
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </Icon>
);
const PencilIcon = (props) => (
  <Icon {...props}>
    <path d="M12 20h9" />
    <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z" />
  </Icon>
);
const HomeIcon = (props) => (
  <Icon {...props}>
    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    <polyline points="9 22 9 12 15 12 15 22" />
  </Icon>
);

const MailIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <polyline points="3 7 12 13 21 7" />
  </Icon>
);

const BADGE_TONES = {
  on: 'bg-emerald-50 text-emerald-800 border-emerald-200',
  off: 'bg-red-50 text-red-700 border-red-200',
  warning: 'bg-amber-50 text-amber-800 border-amber-200',
  unknown: 'bg-gray-100 text-gray-500 border-gray-200',
};
const BADGE_DOTS = { on: 'bg-emerald-500', off: 'bg-red-500', warning: 'bg-amber-500', unknown: 'bg-gray-400' };

/** Shared shape of every pill in the profile header: same height, radius, padding, font and border. Only colours differ. */
const BADGE_BASE = 'inline-flex items-center gap-1.5 h-7 px-3 rounded-md text-xs font-semibold leading-none whitespace-nowrap border';
const ROLE_BADGE_TONE = 'bg-teal-50 text-teal-800 border-teal-200';
const SCOPE_BADGE_TONE = 'bg-gray-100 text-gray-600 border-gray-200';

/** A small status pill: green when the backend says yes, red (or amber with offTone="warning") when it says no, grey while unknown. */
function StatusBadge({ state, label, offTone = 'off' }) {
  const tone = state === 'off' ? offTone : state;
  return (
    <span className={`${BADGE_BASE} ${BADGE_TONES[tone]}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${BADGE_DOTS[tone]}`} aria-hidden="true" />
      {label}
    </span>
  );
}

function ProfileCard({ title, description, icon: CardIcon, children }) {
  return (
    <section className="flex flex-col h-full lg:min-h-[280px] bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-label={title}>
      <div className="flex items-center gap-3 px-5 sm:px-6 py-4 border-b border-gray-100">
        <CardIcon className="w-5 h-5 text-gray-500 shrink-0" />
        <div className="min-w-0">
          <h2 className="text-base font-bold text-gray-900">{title}</h2>
          <p className="text-xs sm:text-sm text-gray-500 mt-1">{description}</p>
        </div>
      </div>
      {children}
    </section>
  );
}

/** One label and its value (and an optional action on the right). */
function FieldRow({ label, htmlFor, action = null, children }) {
  return (
    <div className="flex flex-col justify-center flex-1 px-5 sm:px-6 py-5 min-w-0">
      <div className="flex items-center justify-between gap-3 min-h-5">
        {htmlFor
          ? <label htmlFor={htmlFor} className="text-xs font-semibold uppercase tracking-wide text-gray-500">{label}</label>
          : <p className="text-xs font-semibold uppercase tracking-wide text-gray-500">{label}</p>}
        {action}
      </div>
      <div className="mt-1.5 min-w-0">{children}</div>
    </div>
  );
}

export default function ProfilePage() {
  const username = getUsername() || 'admin';
  const role = getUserRole() || 'ADMIN';
  const { profile } = useCurrentUserProfile();
  const savedImageUrl = profile?.profileImageUrl || null;

  const [editorOpen, setEditorOpen] = useState(false);
  const { notice: pictureMessage, show: showPictureNotice, clear: clearPictureNotice } = useTransientNotice(); // dismisses itself
  const [emailDialogOpen, setEmailDialogOpen] = useState(false);
  const [emailChanged, setEmailChanged] = useState(false);
  const [phoneDialogOpen, setPhoneDialogOpen] = useState(false);
  const [phoneChanged, setPhoneChanged] = useState(false);

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

  const handlePictureDone = (updated, message) => {
    setCurrentProfile(updated); // the header avatar and the top navigation read the shared profile, so both change at once
    showPictureNotice(message); // restarts the timer if an earlier notice is still showing
    setEditorOpen(false);
  };

  // Account activity comes from the same shared profile as everything else on this page (no request of its own)
  const created = describeActivity(profile, 'createdAt', 'Unavailable');
  const lastLogin = describeActivity(profile, 'lastLoginAt');
  const passwordChanged = describeActivity(profile, 'passwordChangedAt');

  const status = accountStatus(profile);
  const verification = emailVerification(profile);
  const email = emailAddress(profile);
  const phone = phoneNumber(profile);
  const accessScope = accessScopeFor(role);

  return (
    <div className="w-full max-w-6xl space-y-6">
      {/* Page heading */}
      <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">User Profile</h1>
          <p className="text-gray-500 mt-2">Manage your account details and profile picture.</p>
        </div>
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-sm text-gray-500 shrink-0">
          <Link to="/dashboard" className="hover:text-emerald-600 transition-colors" aria-label="Dashboard">
            <HomeIcon className="w-4 h-4" />
          </Link>
          <span aria-hidden="true">/</span>
          <span className="font-medium text-gray-800">Profile</span>
        </nav>
      </div>

      {/* Profile header: picture, name, username, role and status */}
      <section className="bg-white rounded-2xl border border-gray-200 shadow-sm" aria-label="Profile summary">
        <div className="flex flex-col lg:flex-row lg:items-center gap-6 lg:gap-8 px-5 sm:px-6 py-6 sm:py-[30px]">
          {/* Left: profile information */}
          <div className="flex-1 min-w-0 flex flex-col md:flex-row md:items-center gap-5 md:gap-6">
          {/* Profile picture: the avatar itself opens the editor */}
          <div className="relative w-20 h-20 sm:w-28 sm:h-28 shrink-0 mx-auto md:mx-0">
            <button
              type="button"
              onClick={() => { clearPictureNotice(); setEditorOpen(true); }}
              title="Edit profile picture"
              aria-label="Edit profile picture"
              className="group relative block w-full h-full rounded-full cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2"
            >
              <UserAvatar
                imageUrl={savedImageUrl}
                initials={username.slice(0, 2).toUpperCase()}
                name={username}
                className="w-full h-full rounded-full border-2 border-emerald-500 transition-all duration-200 group-hover:brightness-90 group-hover:shadow-md"
                fallbackClassName="bg-gradient-to-tr from-emerald-600 via-emerald-500 to-teal-400 text-white text-3xl font-bold"
              />
              <span className="absolute bottom-0.5 right-0.5 w-8 h-8 rounded-full bg-emerald-600 text-white border-2 border-white flex items-center justify-center shadow-md opacity-0 scale-90 group-hover:opacity-100 group-hover:scale-100 group-focus-visible:opacity-100 group-focus-visible:scale-100 [@media(hover:none)]:opacity-100 [@media(hover:none)]:scale-100 transition-all duration-200" aria-hidden="true">
                <CameraIcon className="w-4 h-4" />
              </span>
            </button>
          </div>

          <div className="flex-1 min-w-0 flex flex-col lg:flex-row lg:items-center gap-4 lg:gap-8">
            <div className="flex-1 min-w-0 space-y-1 text-center md:text-left">
              <h2 className="text-2xl font-bold leading-tight text-gray-900 break-words">{displayName}</h2>
              <p className="text-sm text-gray-500 font-medium">@{username}</p>
              <p className="text-xs text-gray-400">Taskotel — Hotel Operations, Simplified</p>
              <div className="flex flex-wrap items-center justify-center md:justify-start gap-2 pt-1">
                <span className="sr-only">Account status:</span>
                <StatusBadge state={status.state} label={status.label} />
                <span className="sr-only">System role:</span>
                <span className={`${BADGE_BASE} uppercase tracking-wide ${ROLE_BADGE_TONE}`}>
                  {role}
                </span>
                <span className="sr-only">Access scope:</span>
                <span className={`${BADGE_BASE} ${SCOPE_BADGE_TONE}`}>
                  {accessScope}
                </span>
              </div>
            </div>
          </div>
          </div>

          {/* Divider: vertical beside the information, horizontal when the two sections stack */}
          <div className="hidden lg:block self-stretch w-px bg-gray-200" aria-hidden="true" />
          <div className="lg:hidden h-px bg-gray-200" aria-hidden="true" />

          {/* Right: Account Activity (read-only) */}
          <div role="group" aria-labelledby="account-activity-title" className="min-w-0 lg:w-[22rem] lg:shrink-0">
            <h3 id="account-activity-title" className="text-xs font-semibold uppercase tracking-wide text-gray-500 mb-3 text-center md:text-left">Account Activity</h3>
            <dl className="space-y-3">
              {[['Account Created', created], ['Last Login', lastLogin], ['Last Password Change', passwordChanged]].map(([label, activity]) => (
                <div key={label} className="flex items-baseline justify-between gap-4">
                  <dt className="text-xs text-gray-500 shrink-0">{label}</dt>
                  <dd className={`text-sm font-medium text-right min-w-0 ${activity.known ? 'text-gray-900' : 'text-gray-500'}`}>{activity.text}</dd>
                </div>
              ))}
            </dl>
          </div>
        </div>

        {pictureMessage && (
          <div className="px-5 sm:px-6 pb-5 -mt-1">
            <p role="status" aria-live="polite" className="text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
              {pictureMessage}
            </p>
          </div>
        )}
      </section>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 items-stretch">
        {/* Personal Information */}
        <ProfileCard title="Personal Information" description="How you appear in Taskotel." icon={UserOutlineIcon}>
          <div className="flex flex-1 flex-col divide-y divide-gray-100">
            <FieldRow label="Display Name" htmlFor="display-name" action={!editingName && (
              <button
                type="button"
                onClick={startEditingName}
                className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700 hover:text-emerald-800 cursor-pointer"
              >
                <PencilIcon className="w-3.5 h-3.5" />
                Edit
              </button>
            )}>
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
                    className={`w-full h-11 px-4 rounded-xl border text-sm font-medium text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500 disabled:opacity-60 ${
                      nameError ? 'border-red-400' : 'border-gray-300'
                    }`}
                  />
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2">
                      <button
                        type="submit"
                        disabled={savingName}
                        className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl flex items-center gap-2 cursor-pointer disabled:opacity-80 disabled:cursor-wait"
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
                  <span id="display-name" className="block truncate text-sm font-semibold text-gray-900" title={displayName}>{displayName}</span>
                  {nameSaved && (
                    <p role="status" aria-live="polite" className="mt-2 text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                      {DISPLAY_NAME_MESSAGES.saved}
                    </p>
                  )}
                </>
              )}
            </FieldRow>
            <FieldRow label="Username">
              <span className="block truncate text-sm font-semibold text-gray-900" title={username}>{username}</span>
            </FieldRow>
          </div>
        </ProfileCard>

        {/* Contact Information: shown as stored; changing either is not available yet */}
        <ProfileCard title="Contact Information" description="Where Taskotel can reach you." icon={MailIcon}>
          <div className="flex flex-1 flex-col divide-y divide-gray-100">
            <FieldRow label="Email Address" action={(
              <button
                type="button"
                onClick={() => { setEmailChanged(false); setEmailDialogOpen(true); }}
                disabled={!email}
                className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700 hover:text-emerald-800 cursor-pointer disabled:text-gray-400 disabled:cursor-not-allowed"
              >
                <PencilIcon className="w-3.5 h-3.5" />
                Edit
              </button>
            )}>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 min-w-0">
                {email
                  ? <span className="min-w-0 truncate text-sm font-semibold text-gray-900" title={email}>{email}</span>
                  : <span className="text-sm text-gray-500">{profile ? 'Not available' : 'Loading…'}</span>}
                <span className="sr-only">Email verification:</span>
                <StatusBadge state={verification.state} label={verification.label} offTone="warning" />
              </div>
              {emailChanged && (
                <p role="status" aria-live="polite" className="mt-2 text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                  {EMAIL_CHANGE_MESSAGES.changed}
                </p>
              )}
            </FieldRow>
            <FieldRow label="Phone Number" action={(
              <button
                type="button"
                onClick={() => { setPhoneChanged(false); setPhoneDialogOpen(true); }}
                disabled={!profile}
                className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700 hover:text-emerald-800 cursor-pointer disabled:text-gray-400 disabled:cursor-not-allowed"
              >
                <PencilIcon className="w-3.5 h-3.5" />
                {phone ? 'Edit' : 'Add'}
              </button>
            )}>
              {phone
                ? <span className="block truncate text-sm font-semibold text-gray-900" title={phone}>{phone}</span>
                : <span className="text-sm text-gray-500">{profile ? 'Not added' : 'Loading…'}</span>}
              {phoneChanged && (
                <p role="status" aria-live="polite" className="mt-2 text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg px-3 py-2">
                  {PHONE_MESSAGES.changed}
                </p>
              )}
            </FieldRow>
          </div>
        </ProfileCard>
      </div>

      <EmailChangeDialog
        isOpen={emailDialogOpen}
        currentEmail={email}
        onClose={() => setEmailDialogOpen(false)}
        onChanged={(updated) => {
          setCurrentProfile(updated); // the page (and anything else reading the shared profile) shows the new address and status at once
          setEmailChanged(true);
          setEmailDialogOpen(false);
        }}
      />

      <PhoneChangeDialog
        isOpen={phoneDialogOpen}
        currentPhone={phone}
        onClose={() => setPhoneDialogOpen(false)}
        onChanged={(updated) => {
          setCurrentProfile(updated); // the page shows the saved number at once
          setPhoneChanged(true);
          setPhoneDialogOpen(false);
        }}
      />

      <ProfilePictureEditor
        isOpen={editorOpen}
        savedImageUrl={savedImageUrl}
        onClose={() => setEditorOpen(false)}
        onDone={handlePictureDone}
      />
    </div>
  );
}
