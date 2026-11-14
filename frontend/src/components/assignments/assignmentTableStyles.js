// Visual-only helpers for the Current Staff Assignments card. They choose classes and display text; they hold no data, filtering,
// pagination or permission logic. Colours are ordinary Tailwind classes, which the theme maps to the charcoal palette in dark mode
// (src/theme/themePalette.cjs); the few dark: classes are deliberate dark-mode tuning.

/** Status badge colours: a soft tinted pill with a small solid dot. The hues match the Tasks table (getStatusColor). */
const STATUS_TONES = {
  PENDING: { badge: 'bg-amber-50 text-amber-800 ring-amber-200/80', dot: 'bg-amber-500' },
  ASSIGNED: { badge: 'bg-blue-50 text-blue-800 ring-blue-200/80', dot: 'bg-blue-500' },
  IN_PROGRESS: { badge: 'bg-purple-50 text-purple-800 ring-purple-200/80', dot: 'bg-purple-500' },
  COMPLETED: { badge: 'bg-emerald-50 text-emerald-800 ring-emerald-200/80', dot: 'bg-emerald-500' },
  CANCELLED: { badge: 'bg-gray-100 text-gray-600 ring-gray-200', dot: 'bg-gray-400' },
  OTHER: { badge: 'bg-gray-100 text-gray-700 ring-gray-200', dot: 'bg-gray-400' },
};

/** The badge and dot classes for a task status (any case or spacing; 'Active' reads as Assigned). */
export function assignmentStatusTone(status) {
  const key = String(status || '').trim().toUpperCase().replace(/\s+/g, '_');
  return STATUS_TONES[key === 'ACTIVE' ? 'ASSIGNED' : key] || STATUS_TONES.OTHER;
}

/** Up to two initials for the staff avatar ("Rahul Sharma" -> "RS", "priya" -> "PR"); '?' when there is no name. */
export function staffInitials(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

// Search field and dropdowns: one height, radius, border and focus treatment; a set filter gets a green tint and border so it
// is easy to see which ones are active (in dark mode the tint needs dark: classes to beat the global dark field background).
const CONTROL_BASE =
  'w-full h-9 rounded-lg border text-sm shadow-xs transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-500/20 focus:border-emerald-500';
const CONTROL_IDLE = 'bg-white border-gray-200 text-gray-800 hover:border-gray-300';
const CONTROL_ACTIVE = 'bg-emerald-50/60 border-emerald-300 text-emerald-900 dark:bg-emerald-500/10 dark:border-emerald-500/40';

/** Classes for a search field or dropdown, highlighted while it holds a value. */
export function filterControlClass(active) {
  return `${CONTROL_BASE} ${active ? CONTROL_ACTIVE : CONTROL_IDLE}`;
}
