// Writes the static Taskotel SVGs in public/ from the same geometry the React logo uses.
// Run from the frontend folder: node scripts/generate-brand-assets.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
  WINDOWS,
  WORDMARK,
} from '../src/components/brand/taskotelMark.js';

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');

const attrs = (object) => Object.entries(object).map(([key, value]) => `${key}="${value}"`).join(' ');
const gradient = (id, { x1, y1, x2, y2, stops }) =>
  `<linearGradient id="${id}" ${attrs({ x1, y1, x2, y2 })} gradientUnits="userSpaceOnUse">` +
  stops.map(([offset, color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join('') +
  '</linearGradient>';

function mark(palette) {
  const fill = (key) => (typeof palette[key] === 'string' ? palette[key] : `url(#${key})`);
  const defs = Object.entries(palette)
    .filter(([, value]) => typeof value === 'object' && !Array.isArray(value))
    .map(([key, spec]) => gradient(key, spec));
  return {
    defs,
    body: [
      `<path d="${MARK_PATHS.roofLeft}" fill="${fill('roofLeft')}"/>`,
      `<path d="${MARK_PATHS.roofRight}" fill="${fill('roofRight')}"/>`,
      `<rect ${attrs(DOOR)} fill="${fill('door')}"/>`,
      `<path d="${MARK_PATHS.tower}" fill="${fill('tower')}"/>`,
      ...WINDOWS.map(({ row, ...rect }) => `<rect ${attrs(rect)} fill="${palette.windows[row]}"/>`),
      `<path d="${MARK_PATHS.clipboard}" fill="${fill('clipboard')}"/>`,
      ...CHECKLIST_LINES.map((rect) => `<rect ${attrs(rect)} fill="${palette.clipboardInk}"/>`),
      `<path d="${MARK_PATHS.check}" stroke="${palette.clipboardInk}" stroke-width="${CHECK_STROKE_WIDTH}" stroke-linecap="round" stroke-linejoin="round"/>`,
      `<path d="${MARK_PATHS.wave}" fill="${fill('wave')}"/>`,
    ],
  };
}

function svg(viewBox, label, defs, body) {
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" fill="none" role="img" aria-label="${label}">`,
    `  <defs>${defs.join('')}</defs>`,
    ...body.map((line) => `  ${line}`),
    '</svg>',
    '',
  ].join('\n');
}

function appIcon() {
  const { defs, body } = mark(MARK_PALETTES.dark);
  const tile = `<rect x="${ICON_TILE.x}" y="${ICON_TILE.y}" width="${ICON_TILE.size}" height="${ICON_TILE.size}" rx="${ICON_TILE.radius}" fill="url(#tile)"/>`;
  return svg(ICON_VIEWBOX, 'Taskotel', [gradient('tile', ICON_TILES.light), ...defs], [tile, ...body]);
}

function horizontal(theme) {
  const { defs, body } = mark(MARK_PALETTES[theme]);
  const colors = WORDMARK.colors[theme];
  const font = WORDMARK.fontFamily.replace(/'/g, '&apos;');
  const { name, tagline } = WORDMARK;
  return svg(HORIZONTAL_VIEWBOX, 'Taskotel — Hotel Operations, Simplified', defs, [
    ...body,
    `<text x="${name.x}" y="${name.y}" font-family="${font}" font-weight="800" font-size="${name.fontSize}" textLength="${name.textLength}" lengthAdjust="spacingAndGlyphs"><tspan fill="${colors.task}">Task</tspan><tspan fill="${colors.otel}">otel</tspan></text>`,
    `<text x="${tagline.x}" y="${tagline.y}" font-family="${font}" font-weight="600" font-size="${tagline.fontSize}" textLength="${tagline.textLength}" lengthAdjust="spacing" fill="${colors.tagline}">${tagline.text}</text>`,
  ]);
}

const outputs = {
  'taskotel-icon.svg': appIcon(),
  'taskotel-logo.svg': horizontal('light'),
  'taskotel-logo-dark.svg': horizontal('dark'),
};
for (const [file, content] of Object.entries(outputs)) {
  fs.writeFileSync(path.join(publicDir, file), content);
  console.log(`wrote public/${file}`);
}
