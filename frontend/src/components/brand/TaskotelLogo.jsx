import React, { useId } from 'react';
import {
  CHECK_STROKE_WIDTH,
  CHECKLIST_LINES,
  DOOR,
  HORIZONTAL_VIEWBOX,
  ICON_TILE,
  ICON_TILES,
  ICON_VIEWBOX,
  MARK_PALETTES,
  MARK_PATHS,
  MARK_VIEWBOX,
  WINDOWS,
  WORDMARK,
} from './taskotelMark';

/*
 * Taskotel logo, recreated from the brand reference sheet: a house roof sheltering
 * a hotel tower, a task checklist with a checkmark, and a sweeping wave below.
 *
 * Variants:
 * - 'mark' / 'icon': the symbol on its own (theme 'light' = full colour, 'dark' = for dark backgrounds)
 * - 'app-icon': the symbol on a rounded square tile (theme 'light' = emerald tile, 'dark' = black tile)
 * - 'horizontal': symbol, Taskotel wordmark and tagline
 */

const Gradient = ({ id, spec }) => (
  <linearGradient id={id} x1={spec.x1} y1={spec.y1} x2={spec.x2} y2={spec.y2} gradientUnits="userSpaceOnUse">
    {spec.stops.map(([offset, color]) => (
      <stop key={offset} offset={offset} stopColor={color} />
    ))}
  </linearGradient>
);

function Mark({ palette, id }) {
  const fill = (key) => (typeof palette[key] === 'string' ? palette[key] : `url(#${id(key)})`);
  return (
    <>
      <defs>
        {Object.entries(palette)
          .filter(([, value]) => typeof value === 'object' && !Array.isArray(value))
          .map(([key, spec]) => <Gradient key={key} id={id(key)} spec={spec} />)}
      </defs>
      <path d={MARK_PATHS.roofLeft} fill={fill('roofLeft')} />
      <path d={MARK_PATHS.roofRight} fill={fill('roofRight')} />
      <rect {...DOOR} fill={fill('door')} />
      <path d={MARK_PATHS.tower} fill={fill('tower')} />
      {WINDOWS.map(({ row, ...rect }) => (
        <rect key={`${rect.x}-${rect.y}`} {...rect} fill={palette.windows[row]} />
      ))}
      <path d={MARK_PATHS.clipboard} fill={fill('clipboard')} />
      {CHECKLIST_LINES.map((rect) => (
        <rect key={rect.y} {...rect} fill={palette.clipboardInk} />
      ))}
      <path
        d={MARK_PATHS.check}
        stroke={palette.clipboardInk}
        strokeWidth={CHECK_STROKE_WIDTH}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path d={MARK_PATHS.wave} fill={fill('wave')} />
    </>
  );
}

export default function TaskotelLogo({
  variant = 'mark',
  theme = 'light',
  className = 'w-10 h-10',
  title = 'Taskotel — Hotel Operations, Simplified',
  showTagline = true,
  tagline = WORDMARK.tagline.text,
  ...props
}) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const id = (name) => `taskotel-${name}-${uid}`;
  const isDark = theme === 'dark';

  const viewBox = variant === 'app-icon' ? ICON_VIEWBOX : variant === 'horizontal' ? HORIZONTAL_VIEWBOX : MARK_VIEWBOX;
  // The tile is dark in both themes, so the app icon always uses the light-on-dark mark
  const palette = variant === 'app-icon' || isDark ? MARK_PALETTES.dark : MARK_PALETTES.light;
  const text = WORDMARK.colors[isDark ? 'dark' : 'light'];

  return (
    <svg
      className={className}
      viewBox={viewBox}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={title ? 'img' : undefined}
      aria-label={title || undefined}
      aria-hidden={title ? undefined : 'true'}
      focusable="false"
      {...props}
    >
      {variant === 'app-icon' && (
        <>
          <defs>
            <Gradient id={id('tile')} spec={ICON_TILES[theme] || ICON_TILES.light} />
          </defs>
          <rect
            x={ICON_TILE.x}
            y={ICON_TILE.y}
            width={ICON_TILE.size}
            height={ICON_TILE.size}
            rx={ICON_TILE.radius}
            fill={`url(#${id('tile')})`}
          />
        </>
      )}

      <Mark palette={palette} id={id} />

      {variant === 'horizontal' && (
        <>
          <text
            x={WORDMARK.name.x}
            y={WORDMARK.name.y}
            fontFamily={WORDMARK.fontFamily}
            fontWeight="800"
            fontSize={WORDMARK.name.fontSize}
            textLength={WORDMARK.name.textLength}
            lengthAdjust="spacingAndGlyphs"
          >
            <tspan fill={text.task}>Task</tspan>
            <tspan fill={text.otel}>otel</tspan>
          </text>
          {showTagline && (
            <text
              x={WORDMARK.tagline.x}
              y={WORDMARK.tagline.y}
              fontFamily={WORDMARK.fontFamily}
              fontWeight="600"
              fontSize={WORDMARK.tagline.fontSize}
              textLength={WORDMARK.tagline.textLength}
              lengthAdjust="spacing"
              fill={text.tagline}
            >
              {tagline}
            </text>
          )}
        </>
      )}
    </svg>
  );
}
