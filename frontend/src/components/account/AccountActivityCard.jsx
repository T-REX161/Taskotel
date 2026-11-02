import React from 'react';
import useCurrentUserProfile from '../../hooks/useCurrentUserProfile';
import { describeActivity } from '../../utils/accountActivity';

function Icon({ className = 'w-5 h-5', children }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}

const ActivityIcon = (props) => (
  <Icon {...props}>
    <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
  </Icon>
);
const CalendarIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <line x1="16" y1="2" x2="16" y2="6" />
    <line x1="8" y1="2" x2="8" y2="6" />
    <line x1="3" y1="10" x2="21" y2="10" />
  </Icon>
);
const ClockIcon = (props) => (
  <Icon {...props}>
    <circle cx="12" cy="12" r="10" />
    <polyline points="12 6 12 12 16 14" />
  </Icon>
);
const KeyIcon = (props) => (
  <Icon {...props}>
    <circle cx="7.5" cy="15.5" r="4.5" />
    <path d="M10.7 12.3L21 2" />
    <path d="M16 7l3 3" />
  </Icon>
);

// A real timestamp reads like any other field; "Loading…" / "Not recorded yet" are muted so they are never mistaken for data
function ActivityField({ label, icon: FieldIcon, activity }) {
  return (
    <div className="space-y-2.5 min-w-0">
      <p className="text-sm font-semibold text-gray-700">{label}</p>
      <div
        className={`flex items-center gap-3 px-4 h-12 rounded-xl border text-sm font-medium min-w-0 bg-gray-50 border-gray-200 ${
          activity.known ? 'text-gray-800' : 'text-gray-500'
        }`}
      >
        <FieldIcon className="w-4 h-4 shrink-0 text-gray-500" />
        <span className="truncate">{activity.text}</span>
      </div>
    </div>
  );
}

/**
 * Account Activity: the server's own timestamps for the signed-in account (created, last sign-in, last password change).
 * It reads the shared, once-loaded profile (the same cache as the header and the profile page), so it makes no request of its own.
 * A value the server has not recorded says so instead of showing a date.
 */
export default function AccountActivityCard() {
  const { profile } = useCurrentUserProfile();
  const created = describeActivity(profile, 'createdAt', 'Unavailable');
  const lastLogin = describeActivity(profile, 'lastLoginAt');
  const passwordChanged = describeActivity(profile, 'passwordChangedAt');

  return (
    <section className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden" aria-labelledby="account-activity-title">
      <div className="flex items-center gap-4 px-6 sm:px-8 py-6 border-b border-gray-100">
        <ActivityIcon className="w-7 h-7 text-gray-500 shrink-0" />
        <div className="min-w-0">
          <h2 id="account-activity-title" className="text-lg font-bold text-gray-900">Account Activity</h2>
          <p className="text-sm text-gray-500 mt-0.5">Key dates for your account.</p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-7 px-6 sm:px-8 py-7">
        <ActivityField label="Account Created" icon={CalendarIcon} activity={created} />
        <ActivityField label="Last Login" icon={ClockIcon} activity={lastLogin} />
        <ActivityField label="Last Password Change" icon={KeyIcon} activity={passwordChanged} />
      </div>
    </section>
  );
}
