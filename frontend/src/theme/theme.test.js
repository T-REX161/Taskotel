import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const colors = require('tailwindcss/colors');
const { SURFACES, darkOverrides, hexToRgb, paletteFor, themeVariables } = require('./themePalette.cjs');

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.join(__dirname, '..', '..');
const read = (...parts) => fs.readFileSync(path.join(frontendDir, ...parts), 'utf8');

const palette = { ...colors, white: '#fff' };
const luminance = (hex) => {
  const [r, g, b] = hexToRgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

describe('Tailwind config loading', () => {
  it('is CommonJS, so a running dev server reloads it after an edit', () => {
    // Tailwind 3 loads the config with require(). In this "type": "module" package an ES-module config is cached by
    // Node's ES-module loader (Node 22.12+), which Tailwind cannot clear: the dev server kept serving the old config.
    for (const name of ['tailwind.config.js', 'tailwind.config.mjs', 'tailwind.config.ts']) {
      assert.ok(!fs.existsSync(path.join(frontendDir, name)), `${name} would be picked up before tailwind.config.cjs`);
    }
    const config = read('tailwind.config.cjs');
    assert.ok(config.includes('module.exports = {') && !/^\s*(import|export)\s/m.test(config), 'CommonJS');
    assert.ok(config.includes("require('./src/theme/themePalette.cjs')"), 'The palette is CommonJS too');
    assert.ok(config.includes("darkMode: 'class'"), 'dark: utilities follow the toggle, not the operating system');
    const loaded = require('../../tailwind.config.cjs');
    assert.strictEqual(loaded.darkMode, 'class');
  });
});

describe('dark mode palette', () => {
  const overrides = darkOverrides(palette);
  const { light, dark } = themeVariables(palette, overrides);

  it('light mode keeps the exact original colours', () => {
    assert.strictEqual(light['--tk-bg-white'], '255 255 255');
    assert.strictEqual(light['--tk-surface-page'], '248 250 252', 'Page background #f8fafc');
    assert.strictEqual(light['--tk-surface-chrome'], '255 255 255', 'Header and sidebar stay white');
    for (const [name, value] of Object.entries(light)) {
      if (name.startsWith('--tk-surface-')) continue;
      const [, prop, color, shade] = name.match(/^--tk-([a-z]+)-([a-z]+)(?:-(\d+))?$/);
      const expected = shade ? palette[color][shade] : palette[color];
      assert.strictEqual(value, hexToRgb(expected).join(' '), `${name} (${prop})`);
    }
    assert.deepStrictEqual(Object.keys(light).sort(), Object.keys(dark).sort());
  });

  it('dark mode surfaces: near-black page, charcoal cards, header and sidebar in between', () => {
    assert.deepStrictEqual([SURFACES.dark.page, SURFACES.dark.chrome, SURFACES.dark.card], ['#090909', '#101010', '#181818']);
    assert.strictEqual(overrides.bg.gray[50], '#202020', 'Table headers and form fields');
    assert.strictEqual(overrides.border.gray[200], '#2a2a2a');
    assert.deepStrictEqual([overrides.text.gray[900], overrides.text.gray[500], overrides.text.gray[400]], ['#f5f5f5', '#a3a3a3', '#737373'], 'Primary, secondary, muted');
    for (const hex of [...Object.values(SURFACES.dark), ...Object.values(overrides.bg.gray), ...Object.values(overrides.text.gray), ...Object.values(overrides.border.gray)]) {
      const [r, g, b] = hexToRgb(hex);
      assert.ok(r === g && g === b, hex + ' is a neutral grey (no blue tint)');
    }
    assert.strictEqual(overrides.bg.white, SURFACES.dark.card, 'Cards, tables, modals and menus');
    const [page, chrome, card, raised] = [SURFACES.dark.page, SURFACES.dark.chrome, SURFACES.dark.card, overrides.bg.gray[50]].map(luminance);
    assert.ok(page < chrome && chrome < card && card < raised, 'Each layer is a step lighter than the one behind it');
  });

  it('solid accents keep their colour; opacity modifiers keep working', () => {
    assert.ok(!overrides.bg.emerald[600] && !overrides.bg.emerald[700], 'Buttons keep their emerald fill and hover');
    assert.ok(!overrides.bg.red[500], 'Bars and dots keep their colour');
    assert.strictEqual(paletteFor('bg', palette, overrides).emerald[600], colors.emerald[600]);
    assert.match(paletteFor('bg', palette, overrides).white, /^rgb\(var\(--tk-bg-white\) \/ <alpha-value>\)$/);
  });

  it('dark mode text stays readable on cards and on the page', () => {
    for (const surface of [SURFACES.dark.card, SURFACES.dark.page, SURFACES.dark.chrome, overrides.bg.gray[50]]) {
      for (const shade of [700, 800, 900]) {
        assert.ok(contrast(overrides.text.gray[shade], surface) >= 7, `text-gray-${shade} on ${surface}`);
      }
      for (const shade of [500, 600]) {
        assert.ok(contrast(overrides.text.gray[shade], surface) >= 4.5, `secondary text-gray-${shade} on ${surface}`);
      }
    }
    for (const hue of ['emerald', 'green', 'red', 'amber', 'yellow', 'orange', 'blue', 'indigo', 'purple', 'cyan', 'rose']) {
      for (const [text, bg] of [[700, 50], [700, 100], [800, 100], [800, 50]]) {
        assert.ok(contrast(overrides.text[hue][text], overrides.bg[hue][bg]) >= 4.5, `text-${hue}-${text} on bg-${hue}-${bg}`);
      }
    }
  });
});

describe('theme wiring', () => {
  const sourceFiles = (dir) =>
    fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return sourceFiles(full);
      return /\.(jsx?|html)$/.test(entry.name) && !entry.name.endsWith('.test.js') ? [full] : [];
    });

  it('layout surfaces use the theme tokens (light values unchanged)', () => {
    const shell = read('src', 'components', 'layout', 'AppShell.jsx');
    assert.strictEqual((shell.match(/bg-surface-page/g) || []).length, 2, 'Page wrapper and main area');
    assert.ok(read('src', 'components', 'layout', 'Header.jsx').includes('border-b border-gray-200 bg-surface-chrome'));
    assert.ok(read('src', 'components', 'layout', 'Sidebar.jsx').includes('w-60 bg-surface-chrome border-r'));
  });

  it('no dark: variant is written as a hex value, so every dark colour comes from the palette', () => {
    const offenders = [...sourceFiles(path.join(frontendDir, 'src')), path.join(frontendDir, 'index.html')]
      .filter((file) => /dark:[\w:-]*\[#/.test(fs.readFileSync(file, 'utf8')))
      .map((file) => path.relative(frontendDir, file));
    assert.deepStrictEqual(offenders, []);
    assert.ok(!/#[0-9a-f]{6}/i.test(read('src', 'index.css').slice(read('src', 'index.css').indexOf('html.dark,'), read('src', 'index.css').indexOf('#root {'))),
      'index.css dark defaults use the variables');
  });

  it('one provider wraps the whole app and the toggle lives in the header', () => {
    assert.ok(read('src', 'main.jsx').includes('<ThemeProvider>'));
    const header = read('src', 'components', 'layout', 'Header.jsx');
    assert.ok(header.includes('const { isDark, toggleTheme } = useTheme();'));
    assert.ok(header.includes("isDark ? 'Switch to light mode' : 'Switch to dark mode'"), 'Tooltip and accessible name');
    assert.ok(header.includes('isDark ? <MoonIcon') && header.includes(': <SunIcon'), 'Sun in light mode, Moon in dark mode');
    assert.ok(header.indexOf('Theme toggle') < header.indexOf('{/* Divider */}'), 'Just left of the divider');
    assert.ok(!header.includes('Notification Indicator Dot'), 'The standalone dot is gone');
    assert.ok(header.includes('{/* Online Status Dot */}'), 'The avatar status dot stays');
  });

  it('the theme is saved and applied before the first paint with the same key', () => {
    const context = read('src', 'theme', 'ThemeContext.jsx');
    assert.ok(context.includes("export const THEME_STORAGE_KEY = 'taskotel-theme';"));
    assert.ok(context.includes('localStorage.setItem(THEME_STORAGE_KEY, theme)'));
    const html = read('index.html');
    const script = html.indexOf("localStorage.getItem('taskotel-theme') === 'dark'");
    assert.ok(script > 0 && script < html.indexOf('<link href="https://fonts.googleapis.com'), 'Inline script runs before styles load');
  });

  it('only the switch itself animates, and reduced motion is respected', () => {
    const context = read('src', 'theme', 'ThemeContext.jsx');
    assert.ok(context.includes("root.classList.add('theme-transition')") && context.includes('prefers-reduced-motion: reduce'));
    assert.ok(context.includes('duration + TRANSITION_SLACK_MS'), 'Fallback: the class outlives the animation, so it is never cut short');
    assert.ok(context.indexOf("const duration = cssDuration(root, '--theme-transition-duration'") < context.indexOf("root.classList.add('theme-transition')"),
      'The duration is read before any class changes (no extra full-page restyle)');
    const css = read('src', 'index.css');
    assert.ok(css.includes('--theme-transition-duration: 300ms;'));
    assert.ok(css.includes('--theme-transition-easing: cubic-bezier(0.4, 0, 0.2, 1);'), 'ease-in-out');
    assert.ok(/@media \(prefers-reduced-motion: reduce\) \{\s*html\.theme-transition,[\s\S]*?transition: none;[\s\S]*?animation: none;/.test(css), 'No animation with reduced motion');
  });

  it('prefers a GPU cross-fade (view transition) scoped to the theme switch', () => {
    const context = read('src', 'theme', 'ThemeContext.jsx');
    assert.ok(context.includes("typeof document.startViewTransition === 'function'"), 'Feature-detected, with the colour-transition fallback');
    assert.ok(/document\.startViewTransition\(\(\) => \{\s*setRootTheme\(root, theme\);/.test(context), 'The theme changes once, under the snapshot');
    assert.ok(context.includes('if (pendingTheme !== theme) setRootTheme(root, theme);'), 'A re-render cannot pre-empt the running transition');
    const css = read('src', 'index.css');
    assert.ok(/html\.theme-switching::view-transition-old\(root\),\s*html\.theme-switching::view-transition-new\(root\) \{\s*animation-duration: var\(--theme-transition-duration\);\s*animation-timing-function: var\(--theme-transition-easing\);/.test(css));
  });

  it('the toggle reveals the new theme in a circle growing from the clicked button', () => {
    const header = read('src', 'components', 'layout', 'Header.jsx');
    assert.ok(/const rect = event\.currentTarget\.getBoundingClientRect\(\);\s*toggleTheme\(\{ x: rect\.left \+ rect\.width \/ 2, y: rect\.top \+ rect\.height \/ 2 \}\);/.test(header),
      'Origin = centre of the toggle, in viewport pixels');
    const context = read('src', 'theme', 'ThemeContext.jsx');
    assert.ok(context.includes('Math.hypot(Math.max(origin.x, window.innerWidth - origin.x), Math.max(origin.y, window.innerHeight - origin.y))'), 'Radius reaches the farthest corner');
    assert.ok(context.includes("pseudoElement: '::view-transition-new(root)'") && context.includes('`circle(0px at ${x}px ${y}px)`'), 'The new theme is revealed, the old stays underneath (crisp fallback)');
    assert.ok(context.includes('transition.ready'), 'The circle starts once the old and new snapshots exist');
    assert.ok(context.includes("latestTheme.current === 'dark' ? 'light' : 'dark'"), 'Rapid clicks always flip from the latest choice');
    const css = read('src', 'index.css');
    assert.ok(css.includes('--theme-reveal-duration: 900ms;') && css.includes('--theme-reveal-easing: cubic-bezier(0.4, 0, 0.2, 1);'), 'Relaxed, gentle curve');
    // Soft edge: a feathered radial mask whose radius is a registered (animatable) length, ending beyond the corner
    assert.ok(/@property --tk-reveal-radius \{\s*syntax: '<length>';\s*inherits: false;\s*initial-value: 0px;/.test(css));
    assert.ok(css.includes('mask-image: radial-gradient(circle at var(--tk-reveal-x) var(--tk-reveal-y), #000 calc(var(--tk-reveal-radius) - var(--tk-reveal-feather)), transparent var(--tk-reveal-radius));'));
    assert.ok(context.includes("{ '--tk-reveal-radius': ['0px', `${reach + feather}px`] }"), 'The soft rim also clears the farthest corner');
    assert.ok(context.includes("fill: 'both'") && context.includes('revealAnimation?.cancel()'), 'Held fully revealed until teardown, then released');
    assert.ok(/html\.theme-switching\.theme-reveal::view-transition-old\(root\),\s*html\.theme-switching\.theme-reveal::view-transition-new\(root\) \{\s*animation: none;\s*mix-blend-mode: normal;/.test(css), 'No cross-fade under the reveal');
    assert.ok(/theme-reveal::view-transition-new\(root\) \{\s*z-index: 1;/.test(css), 'New theme above the old one');
  });

  it('the switch animates colours only, never layout', () => {
    const css = read('src', 'index.css');
    const block = css.slice(css.indexOf('html.theme-transition *::placeholder {'), css.indexOf('@media (prefers-reduced-motion'));
    const properties = block.match(/transition-property: ([^;]+);/)[1].split(',').map((p) => p.trim());
    assert.deepStrictEqual(properties, ['background-color', 'border-color', 'color', 'fill', 'stroke', 'outline-color', 'text-decoration-color', 'caret-color', 'box-shadow']);
    for (const layout of ['all', 'width', 'height', 'padding', 'margin', 'transform', 'top', 'left', 'opacity']) {
      assert.ok(!properties.includes(layout), layout);
    }
    assert.ok(!block.includes('!important'), 'Scoped by the class, not forced');
  });
});
