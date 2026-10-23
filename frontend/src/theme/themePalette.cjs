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
  // Neutral black and charcoal (no blue tint)
  dark: {
    page: '#090909', // page background
    chrome: '#101010', // header and sidebar: a step above the page, below the cards
    card: '#181818', // cards, panels, table rows, modals and menus (what bg-white becomes)
  },
};

// Neutral greys (Tailwind gray and slate) in dark mode, by property and shade.
// Light shades used as backgrounds become surfaces a step above the card (table headers, hover rows, inputs,
// chips); dark shades used as text become light. Dark backgrounds (700-950: avatars, tooltips, overlays) stay
// dark so white text on them still reads.
const NEUTRAL = {
  bg: {
    50: '#202020', 100: '#262626', 200: '#2e2e2e', 300: '#404040', 400: '#5c5c5c',
    500: '#737373', 600: '#8f8f8f', 700: '#333333', 800: '#2a2a2a', 900: '#050505', 950: '#000000',
  },
  text: {
    // 900 primary, 500 secondary, 400 muted
    300: '#525252', 400: '#737373', 500: '#a3a3a3', 600: '#b8b8b8', 700: '#d4d4d4', 800: '#e5e5e5',
    900: '#f5f5f5', 950: '#fafafa',
  },
  border: { 50: '#202020', 100: '#242424', 200: '#2a2a2a', 300: '#3a3a3a', 400: '#4d4d4d' },
  ring: { 100: '#242424', 200: '#2a2a2a', 300: '#3a3a3a' },
  placeholder: { 300: '#5a5a5a', 400: '#6e6e6e', 500: '#858585' },
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
