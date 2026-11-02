import React, { useEffect, useState } from 'react';
import useCurrentUserProfile from '../../hooks/useCurrentUserProfile';
import { getPreferences } from '../../utils/preferences';
import { clockParts, startClock } from '../../utils/liveClock';

/**
 * A compact live date and time for the Dashboard header: the browser's local time, ticking every second, in the signed-in user's
 * date and time formats (AM/PM only in 12-hour). Reading the shared profile makes it re-render the moment a preference changes.
 * The timer is stopped when the Dashboard goes away.
 */
export default function LiveClock() {
  useCurrentUserProfile(); // re-render when the profile (and so the preferences) changes
  const [now, setNow] = useState(() => new Date());

  useEffect(() => startClock(setNow), []);

  const { date, time } = clockParts(now, getPreferences());
  return (
    <time
      dateTime={now.toISOString()}
      className="inline-flex flex-wrap items-baseline gap-x-2 text-sm font-medium text-gray-700 tabular-nums sm:pt-1.5"
      aria-label="Current date and time"
    >
      <span>{date}</span>
      <span aria-hidden="true" className="text-gray-300">·</span>
      <span className="font-semibold text-gray-800">{time}</span>
    </time>
  );
}
