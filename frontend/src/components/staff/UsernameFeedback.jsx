import React from 'react';
import { USERNAME_INVALID_MESSAGES } from '../../utils/username';

const Spinner = () => (
  <svg className="animate-spin w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" aria-hidden="true">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
  </svg>
);

const StatusIcon = ({ path }) => (
  <svg className="w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5} aria-hidden="true">
    <path strokeLinecap="round" strokeLinejoin="round" d={path} />
  </svg>
);

const CHECK = 'M5 13l4 4L19 7';
const CROSS = 'M6 18L18 6M6 6l12 12';
const ALERT = 'M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z';

/** Availability line and clickable alternatives shown under the username input. */
export default function UsernameFeedback({ check, onPickSuggestion, disabled }) {
  const { status, reason, suggestions, suggestionsLoading } = check;
  if (status === 'idle') return null;

  let tone = 'text-gray-500';
  let icon = <Spinner />;
  let text = 'Checking availability…';
  if (status === 'available') {
    tone = 'text-emerald-600';
    icon = <StatusIcon path={CHECK} />;
    text = 'Username available';
  } else if (status === 'taken') {
    tone = 'text-red-600';
    icon = <StatusIcon path={CROSS} />;
    text = 'Username already taken';
  } else if (status === 'error') {
    tone = 'text-amber-600';
    icon = <StatusIcon path={ALERT} />;
    text = 'Could not check availability. The server will verify it when you save.';
  } else if (status === 'invalid') {
    tone = 'text-red-600';
    icon = <StatusIcon path={ALERT} />;
    text = USERNAME_INVALID_MESSAGES[reason] || 'This username cannot be used.';
  }

  return (
    <div className="space-y-1.5">
      <p role="status" aria-live="polite" className={`flex items-start gap-1.5 text-xs leading-4 ${tone}`}>
        <span className="mt-px">{icon}</span>
        <span>{text}</span>
      </p>
      {status === 'taken' && (suggestionsLoading || suggestions.length > 0) && (
        <div className="flex flex-wrap items-center gap-1.5" aria-label="Available alternatives">
          <span className="text-xs text-gray-500">Try:</span>
          {suggestionsLoading && suggestions.length === 0 ? (
            <span className="text-xs text-gray-400">finding alternatives…</span>
          ) : (
            suggestions.map((suggestion) => (
              <button
                key={suggestion}
                type="button"
                disabled={disabled}
                onClick={() => onPickSuggestion(suggestion)}
                className="px-2 py-0.5 rounded-full border border-emerald-200 bg-emerald-50 text-emerald-700 text-xs font-medium hover:bg-emerald-100 transition-colors cursor-pointer disabled:opacity-50"
              >
                {suggestion}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
