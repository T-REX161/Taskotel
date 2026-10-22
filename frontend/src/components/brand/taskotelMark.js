/*
 * Taskotel mark geometry and palettes, traced from the brand reference sheet.
 * Coordinates are in the sheet's pixel space, so every variant shares one
 * drawing and only changes the viewBox and colours.
 *
 * Used by TaskotelLogo.jsx and by scripts/generate-brand-assets.mjs, which
 * writes the static SVGs in public/ from the same data.
 */

export const MARK_VIEWBOX = '72 63.5 246 246';
export const ICON_VIEWBOX = '25 24 345 345';
export const HORIZONTAL_VIEWBOX = '72 63.5 790 246';

export const ICON_TILE = { x: 25, y: 24, size: 345, radius: 76 };

export const MARK_PATHS = {
  roofLeft: 'M206.5 74.8C202 74.5 199.5 75.5 196 78L111 145.5Q108 148 108 152V220.5Q108 224 111.5 224H132.5Q136 224 136 220.5V157L206.5 103Z',
  roofRight: 'M203.5 74.8C207 74.5 210 76.5 213 79.5C232 97 257 112 274.5 130.5A7.5 7.5 0 0 1 265.5 142C250 129.5 226 113 203.5 103Z',
  tower: 'M201.5 123.8Q205 120.6 208.5 123.8L232 141.2Q233 142 233 143.5V156A18 18 0 0 0 225 171V252.6C211 247.6 193.5 239.2 179.6 231V153.5H178Q176.5 153.5 176.5 152V143.5Q176.5 141.8 178 140.7Z',
  clipboard: 'M245 158.3H287.5Q300.7 158.3 300.7 171.5V236C300.7 254 290 260.7 266.2 260.7C242.4 260.7 231.7 254 231.7 236V171.5Q231.7 158.3 245 158.3Z',
  check: 'M250.5 222.5L261.5 234L283.5 212',
  wave: 'M76 250.5C95 243 113 233.5 131 232C160 233 188 246 206 257C220 265.5 236 270 252 269.8C276 269.5 300 261 314.5 247.5C304 268 288 283 270 291C258 296.5 248 298 236 297.5C210 296 188 285 168 274.5C142 260 114 251 76 250.5Z',
};

export const DOOR = { x: 151, y: 189.5, width: 19, height: 14.5, rx: 2.6 };

// Two columns, three rows; `row` picks the window colour from the palette.
export const WINDOWS = [0, 1, 2].flatMap((row) =>
  [191.5, 205.5].map((x) => ({ x, y: 159 + row * 19.5, width: 7.5, height: 10, rx: 1.2, row }))
);

export const CHECKLIST_LINES = [
  { x: 247, y: 179.5, width: 38, height: 6.4, rx: 3.2 },
  { x: 247, y: 197.8, width: 24.7, height: 6.2, rx: 3.1 },
];

export const CHECK_STROKE_WIDTH = 8;

const vertical = (y1, y2, stops) => ({ x1: 0, y1, x2: 0, y2, stops });

// Fills are either a colour string or a userSpaceOnUse linear gradient.
export const MARK_PALETTES = {
  // Full-colour mark for light backgrounds
  light: {
    roofLeft: vertical(75, 224, [[0, '#14977a'], [0.25, '#22c990'], [0.5, '#30e09d'], [0.7, '#2ad396'], [0.78, '#18a882'], [1, '#0a7f62']]),
    roofRight: { x1: 205, y1: 80, x2: 280, y2: 142, stops: [[0, '#17a27e'], [1, '#0b765c']] },
    door: '#179c7a',
    tower: vertical(121, 253, [[0, '#05554a'], [1, '#033a33']]),
    windows: ['#ffffff', '#76bcad', '#ffffff'],
    clipboard: vertical(158, 261, [[0, '#0f9a75'], [1, '#11a67b']]),
    clipboardInk: '#ffffff',
    wave: { x1: 76, y1: 260, x2: 314.5, y2: 260, stops: [[0, '#12a37b'], [0.23, '#11a079'], [0.42, '#0b8466'], [0.65, '#086b57'], [1, '#075c4c']] },
  },
  // White and mint mark for dark backgrounds and the app icon tile
  dark: {
    roofLeft: vertical(75, 224, [[0, '#ffffff'], [0.6, '#f2fcf8'], [1, '#c9f3e3']]),
    roofRight: { x1: 205, y1: 80, x2: 280, y2: 142, stops: [[0, '#ffffff'], [1, '#e3f8ef']] },
    door: '#9ee2c6',
    tower: vertical(121, 253, [[0, '#ffffff'], [1, '#e6f7f0']]),
    windows: ['#0b5a48', '#0b5a48', '#0b5a48'],
    clipboard: vertical(158, 261, [[0, '#e2f9ef'], [1, '#c8f0e0']]),
    clipboardInk: '#0c5d4a',
    wave: { x1: 76, y1: 260, x2: 314.5, y2: 260, stops: [[0, '#6ad6aa'], [0.45, '#aff2da'], [1, '#8fe3c3']] },
  },
};

export const ICON_TILES = {
  light: vertical(24, 369, [[0, '#13846b'], [1, '#033c35']]),
  dark: vertical(24, 369, [[0, '#10201d'], [1, '#050b0a']]),
};

export const WORDMARK = {
  fontFamily: "'Plus Jakarta Sans', system-ui, -apple-system, 'Segoe UI', sans-serif",
  name: { x: 343, y: 222, fontSize: 132, textLength: 497 },
  tagline: { x: 371, y: 262, fontSize: 24, textLength: 470, text: 'HOTEL OPERATIONS, SIMPLIFIED' },
  colors: {
    light: { task: '#04302a', otel: '#0f9874', tagline: '#4a5a57' },
    dark: { task: '#ffffff', otel: '#3fd6a0', tagline: '#cfe9df' },
  },
};
