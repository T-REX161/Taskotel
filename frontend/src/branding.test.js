import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CHECKLIST_LINES,
  DOOR,
  ICON_TILE,
  ICON_VIEWBOX,
  MARK_PALETTES,
  MARK_PATHS,
  MARK_VIEWBOX,
  WINDOWS,
} from './components/brand/taskotelMark.js';

const __filename = fileURLToPath(import.meta.url);
const srcDir = path.dirname(__filename);
const frontendDir = path.dirname(srcDir);

// Old product names (any case, any spacing/hyphenation) and legacy wordmarks
const OLD_BRANDING = /hotel[\s_-]*(staff[\s_-]*)?workload[\s_-]*balancer|hotel[\s_-]*wb\b|hotel\s*<span[^>]*>\s*wb\s*<|workload balancer|innflow|equiflow/i;

const sourceFiles = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.(jsx?|css|html)$/.test(entry.name) && !entry.name.endsWith('.test.js') ? [full] : [];
  });
const read = (relative) => fs.readFileSync(path.join(frontendDir, relative), 'utf8');

describe('Taskotel branding', () => {
  it('no frontend source file or index.html uses the old product name', () => {
    const offenders = [...sourceFiles(srcDir), path.join(frontendDir, 'index.html')]
      .filter((file) => OLD_BRANDING.test(fs.readFileSync(file, 'utf8')))
      .map((file) => path.relative(frontendDir, file));
    assert.deepStrictEqual(offenders, [], `Old branding found in: ${offenders.join(', ')}`);
  });

  it('sets the browser tab title and metadata to Taskotel', () => {
    const html = read('index.html');
    assert.ok(html.includes('<title>Taskotel — Hotel Operations, Simplified</title>'), 'Tab title');
    assert.ok(html.includes('<meta name="application-name" content="Taskotel" />'), 'Application name meta');
    assert.ok(html.includes('content="Taskotel — Hotel Operations, Simplified. Balanced workloads. Smarter operations."'), 'Description meta');
  });

  it('shows the Taskotel wordmark on every branded screen', () => {
    const wordmark = 'Task<span className="text-[#0f9874]">otel</span>';
    for (const file of [
      'src/pages/LoginPage.jsx',
      'src/pages/ForgotPasswordPage.jsx',
      'src/pages/SetCredentialsPage.jsx',
      'src/pages/EmailVerificationPage.jsx',
    ]) {
      assert.ok(read(file).includes(wordmark), `${file} must show the Taskotel wordmark`);
    }
  });

  it('header and mobile sidebar share the same brand block proportions', () => {
    const appWordmark = 'Task<span className="text-[#0f9874]">otel</span>';
    const wordmarkText = 'text-[17px] font-extrabold tracking-tight text-[#04302a] leading-5';
    for (const file of ['src/components/layout/Header.jsx', 'src/components/layout/Sidebar.jsx']) {
      const source = read(file);
      assert.ok(source.includes(appWordmark), `${file}: two-tone Taskotel wordmark`);
      assert.ok(source.includes('<TaskotelLogo className="w-10 h-10 shrink-0'), `${file}: 40px Taskotel mark`);
      assert.ok(source.includes(wordmarkText), `${file}: 17px extrabold wordmark`);
    }
    const header = read('src/components/layout/Header.jsx');
    assert.ok(
      header.includes('<span className="hidden sm:block text-[9px] font-semibold text-slate-500 tracking-[0.08em] uppercase leading-none mt-1">'),
      'Header subtitle: 9px muted blue-gray with subtle letter spacing, hidden below sm'
    );
    assert.ok(header.includes('<header className="h-16 '), 'Header height unchanged (64px)');
    assert.ok(read('src/components/layout/Sidebar.jsx').includes('h-12 px-2 flex items-center justify-between'), 'Drawer header height unchanged');
  });

  it('uses the Taskotel logo in every branding location and nowhere uses the old building icon or previous logo', () => {
    const authLogo = '<TaskotelLogo className="w-16 h-16 mb-3 transform transition-transform hover:scale-105 duration-200" />';
    for (const file of ['src/pages/ForgotPasswordPage.jsx', 'src/pages/SetCredentialsPage.jsx', 'src/pages/EmailVerificationPage.jsx']) {
      assert.ok(read(file).includes(authLogo), `${file}: 64px Taskotel mark`);
    }
    // The split-screen login places the mark beside the wordmark instead of above it
    assert.ok(read('src/pages/LoginPage.jsx').includes('<TaskotelLogo className="w-[72px] h-[72px] shrink-0 transform transition-transform hover:scale-105 duration-200" />'), 'Login: 72px Taskotel mark');
    const offenders = [...sourceFiles(srcDir), path.join(frontendDir, 'index.html')]
      .filter((file) => /HotelLogoIcon|vite\.svg|EquiFlowLogo|equiflow-logo\.svg/.test(fs.readFileSync(file, 'utf8')))
      .map((file) => path.relative(frontendDir, file));
    assert.deepStrictEqual(offenders, [], `Old logo / favicon references in: ${offenders.join(', ')}`);
  });

  it('favicon and static logos are generated from the same artwork as the React logo component', () => {
    assert.ok(read('index.html').includes('<link rel="icon" type="image/svg+xml" href="/taskotel-icon.svg" />'), 'Favicon link');
    const component = read('src/components/brand/TaskotelLogo.jsx');
    assert.ok(component.includes("from './taskotelMark'"), 'Component draws the shared geometry');
    assert.ok(component.includes("variant = 'mark'"), 'Plain mark is the default variant');

    const favicon = read('public/taskotel-icon.svg');
    assert.ok(favicon.includes(`viewBox="${ICON_VIEWBOX}"`), 'Favicon viewBox');
    assert.ok(favicon.includes(`rx="${ICON_TILE.radius}"`), 'Favicon tile corner radius');
    for (const [file, theme, tiles] of [
      ['public/taskotel-icon.svg', 'dark', 1],
      ['public/taskotel-logo.svg', 'light', 0],
      ['public/taskotel-logo-dark.svg', 'dark', 0],
    ]) {
      const svg = read(file);
      for (const [name, d] of Object.entries(MARK_PATHS)) {
        assert.ok(svg.includes(`d="${d}"`), `${file}: ${name} path`);
      }
      assert.ok(svg.includes(`x="${DOOR.x}" y="${DOOR.y}"`), `${file}: door`);
      assert.strictEqual(svg.split('<rect').length - 1, WINDOWS.length + CHECKLIST_LINES.length + 1 + tiles, `${file}: window, line, door and tile rects`);
      assert.ok(svg.includes(`fill="${MARK_PALETTES[theme].clipboardInk}"`), `${file}: ${theme} palette`);
    }
  });

  it('recreates the reference mark: roof, hotel tower, checklist with checkmark, and wave', () => {
    assert.strictEqual(MARK_VIEWBOX, '72 63.5 246 246');
    assert.deepStrictEqual(Object.keys(MARK_PATHS), ['roofLeft', 'roofRight', 'tower', 'clipboard', 'check', 'wave']);
    assert.strictEqual(WINDOWS.length, 6, 'Two columns of three tower windows');
    assert.strictEqual(CHECKLIST_LINES.length, 2, 'Two checklist lines above the checkmark');

    const light = MARK_PALETTES.light;
    assert.strictEqual(light.tower.stops[0][1], '#05554a', 'Dark green hotel tower');
    assert.ok(light.roofLeft.stops.some(([, color]) => color === '#30e09d'), 'Mint roof highlight');
    assert.strictEqual(light.clipboardInk, '#ffffff', 'White checklist ink on the emerald card');

    for (const [file, task, otel] of [['public/taskotel-logo.svg', '#04302a', '#0f9874'], ['public/taskotel-logo-dark.svg', '#ffffff', '#3fd6a0']]) {
      const svg = read(file);
      assert.ok(svg.includes(`<tspan fill="${task}">Task</tspan><tspan fill="${otel}">otel</tspan>`), `${file}: two-tone wordmark`);
      assert.ok(svg.includes('HOTEL OPERATIONS, SIMPLIFIED'), `${file}: tagline`);
    }
  });

  it('uses the Taskotel name, description and footer consistently', () => {
    const footer = 'Taskotel — Hotel Operations, Simplified &copy; {new Date().getFullYear()} • All rights reserved';
    assert.ok(read('src/pages/LoginPage.jsx').includes('Sign in to your Taskotel account'), 'Login subtitle');
    for (const [file, count] of [['src/pages/LoginPage.jsx', 1], ['src/pages/SetCredentialsPage.jsx', 2], ['src/pages/EmailVerificationPage.jsx', 2]]) {
      assert.strictEqual(read(file).split(footer).length - 1, count, `${file} footer(s)`);
    }
    assert.ok(read('src/components/layout/Header.jsx').includes('title="Taskotel — Hotel Operations, Simplified"'), 'Header logo title');
    assert.ok(read('src/pages/ProfilePage.jsx').includes('Taskotel — Hotel Operations, Simplified'), 'Profile description');
    assert.strictEqual(JSON.parse(read('package.json')).description, 'Taskotel — Hotel Operations, Simplified', 'package.json description');
  });
});
