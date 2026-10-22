import React, { useId } from 'react';

/*
 * EquiFlow logo, recreated as a vector from the supplied reference icon: a deep emerald rounded square with
 * three layered flowing shapes forming an abstract letterform: a white upper swoosh, a mint middle swoosh
 * whose stem curves down to the bottom left, and a darker green lower swoosh that blends into the background.
 *
 * Coordinates are in the reference image's own pixel space (tile at 3.1,3.4, 50 x 50, corner radius 10), so
 * the shapes keep the reference proportions exactly. The artwork is self-contained (background included);
 * callers only set the size and layout classes. public/equiflow-logo.svg (the favicon) uses the same values.
 *
 * Add `rounded-[20%]` to the className when using a shadow, so the shadow follows the tile's rounded corners.
 */
export const EQUIFLOW_LOGO = {
  viewBox: '3.1 3.4 50 50',
  tileRadius: 10,
  backgroundTop: '#2ea685',
  backgroundMiddle: '#178466',
  backgroundBottom: '#127658',
  whiteLight: '#effcf7',
  whiteShade: '#dbf6ec',
  mintLight: '#6edcb0',
  mintMiddle: '#55c99c',
  mintDeep: '#2fae80',
  darkGreen: '#0a644e',
  whitePath: 'M25.5 12.6H44.8Q45.8 12.6 45.4 13.4C44.6 16.8 41.8 21.8 38 21.8H26.5C23.6 21.8 22.3 24.2 21.6 25.2C19.5 26 15.5 26.6 13.4 29.2L13.6 23.5C15 16.5 20 12.6 25.5 12.6Z',
  mintPath: 'M24.5 27.7H37.6C39.6 27.7 40.8 29.5 40.8 31.3C40.8 33.2 39.5 34.8 37.4 34.8H28.5C25 34.8 21.5 36 20.6 38C18.6 40.6 13.5 41.6 11.2 45.8C10.9 39 11.6 34.5 13.8 32.2C16.2 29.6 19.5 27.7 24.5 27.7Z',
  darkPath: 'M19.6 40.2H33.5C40 40.2 46.5 41.6 58 45V58H17.2C17.2 52 16.6 47.4 15.4 45.4C14.6 44 16.8 40.6 19.6 40.2Z',
};

export default function EquiFlowLogo({ className = 'w-9 h-9', title, ...props }) {
  // Unique per instance (several logos can be on screen); only characters valid in url(#...)
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const id = (name) => `equiflow-logo-${name}-${uid}`;
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
        <linearGradient id={id('bg')} x1="28" y1="3.4" x2="28" y2="53.4" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={L.backgroundTop} />
          <stop offset="0.55" stopColor={L.backgroundMiddle} />
          <stop offset="1" stopColor={L.backgroundBottom} />
        </linearGradient>
        <linearGradient id={id('white')} x1="45" y1="12.6" x2="13.4" y2="29.2" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={L.whiteLight} />
          <stop offset="1" stopColor={L.whiteShade} />
        </linearGradient>
        <linearGradient id={id('mint')} x1="11" y1="45.6" x2="40.8" y2="28" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor={L.mintLight} />
          <stop offset="0.55" stopColor={L.mintMiddle} />
          <stop offset="1" stopColor={L.mintDeep} />
        </linearGradient>
        <clipPath id={id('tile')}>
          <rect x="3.1" y="3.4" width="50" height="50" rx={L.tileRadius} />
        </clipPath>
        <filter id={id('soft')} x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="0.9" />
        </filter>
      </defs>
      <rect x="3.1" y="3.4" width="50" height="50" rx={L.tileRadius} fill={`url(#${id('bg')})`} />
      <g clipPath={`url(#${id('tile')})`}>
        <path filter={`url(#${id('soft')})`} fill={L.darkGreen} d={L.darkPath} />
      </g>
      <path fill={`url(#${id('mint')})`} d={L.mintPath} />
      <path fill={`url(#${id('white')})`} d={L.whitePath} />
    </svg>
  );
}
