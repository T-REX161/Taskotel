import React, { useId } from 'react';

/*
 * EquiFlow "Flow E" logo: an emerald rounded square with an abstract letter E made of smooth,
 * flowing horizontal strokes (white top arm, spine and bottom arm; mint middle wave).
 * The artwork is self-contained (background included), so every location renders identical colours;
 * callers only set the size and any layout classes. public/equiflow-logo.svg (the favicon) uses the
 * same geometry and colours.
 *
 * Add `rounded-[28%]` to the className when using a shadow, so the shadow follows the rounded corners.
 */
export const EQUIFLOW_LOGO = {
  viewBox: '0 0 48 48',
  cornerRadius: 13.5,
  gradientFrom: '#10b981', // emerald-500
  gradientTo: '#047857', // emerald-700
  white: '#ffffff',
  mint: '#6ee7b7', // emerald-300
  strokeWidth: 4.5,
  // Top arm, spine and bottom arm in one stroke; the arm tips flick outward ("flow")
  bodyPath: 'M34.5 12.6C32.6 13.6 31 14 28.5 14H20.5C16.9 14 14 16.9 14 20.5V27.5C14 31.1 16.9 34 20.5 34H28.5C31 34 32.6 34.4 34.5 35.4',
  // Shorter middle arm growing out of the spine with a gentle upward wave (drawn first, under the spine)
  middlePath: 'M14.5 24H22.5C25.5 24 27.5 22.8 30.5 22.8',
};

export default function EquiFlowLogo({ className = 'w-9 h-9', title, ...props }) {
  // Unique per instance (several logos can be on screen); only characters valid in url(#...)
  const gradientId = `equiflow-logo-bg-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const L = EQUIFLOW_LOGO;

  return (
    <svg
      className={className}
      viewBox={L.viewBox}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={title ? 'img' : undefined}
      aria-label={title || undefined}
      aria-hidden={title ? undefined : 'true'}
      focusable="false"
      {...props}
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={L.gradientFrom} />
          <stop offset="1" stopColor={L.gradientTo} />
        </linearGradient>
      </defs>
      <rect width="48" height="48" rx={L.cornerRadius} fill={`url(#${gradientId})`} />
      <path d={L.middlePath} stroke={L.mint} strokeWidth={L.strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
      <path d={L.bodyPath} stroke={L.white} strokeWidth={L.strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
