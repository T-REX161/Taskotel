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

  it('dark mode surfaces: deep slate page, lighter cards, header and sidebar in between', () => {
    assert.strictEqual(SURFACES.dark.page, '#0f172a');
    assert.strictEqual(SURFACES.dark.card, '#1e293b');
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
    assert.ok(read('src', 'index.css').includes('html.theme-transition *'));
  });
});
