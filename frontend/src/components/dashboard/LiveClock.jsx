import React, { useEffect, useState } from 'react';
import useCurrentUserProfile from '../../hooks/useCurrentUserProfile';
import { getPreferences } from '../../utils/preferences';
import { clockParts, secondsPart, sharedTicker, splitTime, startClock } from '../../utils/liveClock';

function Icon({ className = 'w-3.5 h-3.5', strokeWidth = 2, children }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}
const CalendarIcon = (props) => (
  <Icon {...props}>
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </Icon>
);

/**
 * The Dashboard's live date and time, with no container and no clock icon: two rows that start at the same left edge. The first row is the
 * time, with the hours and minutes prominent, the seconds smaller and muted right after them, and a small AM/PM (12-hour only); the second
 * row is a small calendar icon and the date.
 *
 * Everything is read from the system clock at the moment it is drawn, in the signed-in user's date and time formats, so the seconds can
 * neither freeze nor drift, and the date changes by itself at midnight. The component follows the page's one shared heartbeat
 * (utils/liveClock): that single timer checks the time several times a second and the component re-renders once per new second; and when
 * the tab becomes active again the heartbeat resyncs at once, instead of waiting for a background-throttled timer. Nothing is left running
 * when the Dashboard goes away. Reading the shared profile makes it re-render the moment a preference changes.
 */
export default function LiveClock() {
  useCurrentUserProfile(); // re-render when the profile (and so the preferences) changes
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    setNow(new Date()); // in case time passed between the first render and the effect
    return startClock(setNow, { subscribe: sharedTicker.subscribe, granularity: 'second' });
  }, []);

  const { date, time } = clockParts(now, getPreferences());
  const { main, meridiem } = splitTime(time);
  return (
    <time
      dateTime={now.toISOString()}
      aria-label="Current date and time"
      className="inline-flex max-w-full flex-col items-start gap-1 tabular-nums sm:mt-1"
    >
      <span className="flex items-baseline gap-1.5 whitespace-nowrap leading-none">
        <span className="inline-flex items-baseline gap-0.5">
          <span className="text-2xl font-semibold leading-[1.05] tracking-tight text-gray-900" aria-hidden="true">{main}</span>
          <span className="text-sm font-medium text-gray-500" aria-hidden="true">:{secondsPart(now)}</span>
        </span>
        {meridiem && <span className="text-xs font-semibold text-gray-500" aria-hidden="true">{meridiem}</span>}
        {/* Screen readers get the time once (hours and minutes), not a new announcement every second */}
        <span className="sr-only">{time}</span>
      </span>

      <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs font-medium leading-none text-gray-500">
        <CalendarIcon className="w-3.5 h-3.5 text-gray-400" />
        {date}
      </span>
    </time>
  );
}
