/*
 * Dark mode palette for Taskotel.
 *
 * The app is styled with ordinary Tailwind colour classes (bg-white, text-gray-900, bg-emerald-50, ...).
 * Instead of adding a dark: variant to every one of them, tailwind.config.cjs points the colours that need a
 * different value in dark mode at CSS variables. Light mode keeps the exact original Tailwind values; dark mode
 * (html.dark) swaps in the values below. The mapping is per CSS property, so for example text-emerald-700 can
 * become light (readable on a dark surface) while bg-emerald-700 on a button stays dark green.
 *
 * CommonJS on purpose (see tailwind.config.cjs): Tailwind loads it with require() and can reload it after edits.
 * No dependencies: the Tailwind config and the tests both use it.
 */

// Surfaces, from back to front. Light values are the ones the app has always used.
const SURFACES = {
  light: { page: '#f8fafc', chrome: '#ffffff', card: '#ffffff' },
  dark: {
    page: '#0f172a', // page background
    chrome: '#141e32', // header and sidebar: a step above the page, below the cards
    card: '#1e293b', // cards, panels, tables, modals, menus and inputs (what bg-white becomes)
  },
};

// Neutral greys (Tailwind gray and slate) in dark mode, by property and shade.
// Light shades used as backgrounds become surfaces a step above the card (table headers, hover rows, chips);
// dark shades used as text become light. Dark backgrounds (700-950: avatars, tooltips, overlays) stay dark so
// white text on them still reads.
const NEUTRAL = {
  bg: {
    50: '#243145', 100: '#2a384e', 200: '#334155', 300: '#475569', 400: '#64748b',
    500: '#7c8ba1', 600: '#94a3b8', 700: '#3b4a61', 800: '#2e3b52', 900: '#0b1120', 950: '#060a14',
  },
  text: {
    300: '#5a6981', 400: '#7d8ca3', 500: '#94a3b8', 600: '#aebacb', 700: '#cbd5e1', 800: '#e2e8f0',
    900: '#f1f5f9', 950: '#f8fafc',
  },
  border: { 50: '#222e42', 100: '#273449', 200: '#2f3d54', 300: '#3e4d66', 400: '#52627c' },
  ring: { 100: '#273449', 200: '#2f3d54', 300: '#3e4d66' },
  placeholder: { 300: '#4f5e76', 400: '#6b7a92', 500: '#7d8ca3' },
};

// Accent hues: pale tints (badges, alerts, selected rows) become low-key tints of the hue on the dark card,
// and dark text shades become the matching light shades. Solid fills (400-950 backgrounds) are unchanged,
// so buttons, bars and status dots keep their colour.
const HUES = [
  'red', 'orange', 'amber', 'yellow', 'lime', 'green', 'emerald', 'teal', 'cyan', 'sky',
  'blue', 'indigo', 'violet', 'purple', 'fuchsia', 'pink', 'rose',
];
const TINT = { bg: { 50: 0.12, 100: 0.2, 200: 0.32 }, border: { 50: 0.12, 100: 0.22, 200: 0.34, 300: 0.5 }, ring: { 100: 0.22, 200: 0.34, 300: 0.5 } };
const TEXT_SHIFT = { 600: '400', 700: '300', 800: '200', 900: '100', 950: '50' };

// Properties whose colours are remapped, with the Tailwind theme key each one feeds
const PROPERTIES = {
  bg: 'backgroundColor',
  text: 'textColor',
  border: 'borderColor',
  ring: 'ringColor',
  placeholder: 'placeholderColor',
  gradient: 'gradientColorStops',
};

function hexToRgb(hex) {
  const value = hex.replace('#', '');
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16));
}

const toHex = (rgb) => '#' + rgb.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
const triplet = (hex) => hexToRgb(hex).join(' ');

// weight of `hex` over `base` (0..1)
function mix(hex, base, weight) {
  const a = hexToRgb(hex);
  const b = hexToRgb(base);
  return toHex(a.map((v, i) => v * weight + b[i] * (1 - weight)));
}

/**
 * The dark-mode value of every remapped colour: { bg: { gray: { 50: '#...' }, white: '#...' }, text: {...}, ... }.
 * `colors` is Tailwind's colour palette (theme('colors')).
 */
function darkOverrides(colors) {
  const result = {};
  for (const prop of Object.keys(PROPERTIES)) {
    const source = prop === 'gradient' ? 'bg' : prop;
    const out = {};
    if (NEUTRAL[source]) {
      out.gray = { ...NEUTRAL[source] };
      out.slate = { ...NEUTRAL[source] };
    }
    for (const hue of HUES) {
      const shades = {};
      for (const [shade, weight] of Object.entries(TINT[source] || {})) {
        shades[shade] = mix(colors[hue][500], SURFACES.dark.card, weight);
      }
      if (source === 'text') {
        for (const [shade, lighter] of Object.entries(TEXT_SHIFT)) shades[shade] = colors[hue][lighter];
      }
      if (Object.keys(shades).length) out[hue] = shades;
    }
    if (['bg', 'border', 'ring'].includes(source)) out.white = SURFACES.dark.card;
    result[prop] = out;
  }
  return result;
}

const varName = (prop, color, shade) => `--tk-${prop}-${color}${shade ? '-' + shade : ''}`;
const lightValue = (colors, color, shade) => (shade ? colors[color][shade] : colors[color]);

/**
 * Tailwind colour palette for one property: the normal palette, with every remapped colour replaced by
 * rgb(var(--tk-...) / <alpha-value>) so opacity modifiers (bg-white/80) and variants (hover:) keep working.
 */
function paletteFor(prop, colors, overrides = darkOverrides(colors)) {
  const palette = { ...colors };
  for (const [color, value] of Object.entries(overrides[prop])) {
    if (typeof value === 'string') {
      palette[color] = `rgb(var(${varName(prop, color)}) / <alpha-value>)`;
    } else {
      palette[color] = { ...colors[color] };
      for (const shade of Object.keys(value)) {
        palette[color][shade] = `rgb(var(${varName(prop, color, shade)}) / <alpha-value>)`;
      }
    }
  }
  return palette;
}

/** Layout surface colours for the Tailwind theme: bg-surface-page, bg-surface-chrome. */
function surfaceColors() {
  return Object.fromEntries(Object.keys(SURFACES.dark).map((name) => [name, `rgb(var(--tk-surface-${name}) / <alpha-value>)`]));
}

/** The CSS variables: light values on :root (exactly the original colours), dark values on html.dark. */
function themeVariables(colors, overrides = darkOverrides(colors)) {
  const light = {};
  const dark = {};
  for (const name of Object.keys(SURFACES.dark)) {
    light[`--tk-surface-${name}`] = triplet(SURFACES.light[name]);
    dark[`--tk-surface-${name}`] = triplet(SURFACES.dark[name]);
  }
  for (const [prop, byColor] of Object.entries(overrides)) {
    for (const [color, value] of Object.entries(byColor)) {
      if (typeof value === 'string') {
        light[varName(prop, color)] = triplet(lightValue(colors, color));
        dark[varName(prop, color)] = triplet(value);
      } else {
        for (const [shade, hex] of Object.entries(value)) {
          light[varName(prop, color, shade)] = triplet(lightValue(colors, color, shade));
          dark[varName(prop, color, shade)] = triplet(hex);
        }
      }
    }
  }
  return { light, dark };
}

module.exports = { SURFACES, HUES, PROPERTIES, hexToRgb, mix, darkOverrides, paletteFor, surfaceColors, themeVariables };
