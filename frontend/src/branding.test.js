import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const srcDir = path.dirname(__filename);
const frontendDir = path.dirname(srcDir);

// Old product names (any case, any spacing/hyphenation) and the split "Hotel <span>WB</span>" wordmark
const OLD_BRANDING = /hotel[\s_-]*(staff[\s_-]*)?workload[\s_-]*balancer|hotel[\s_-]*wb\b|hotel\s*<span[^>]*>\s*wb\s*<|workload balancer|innflow/i;

const sourceFiles = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.(jsx?|css|html)$/.test(entry.name) && !entry.name.endsWith('.test.js') ? [full] : [];
  });
const read = (relative) => fs.readFileSync(path.join(frontendDir, relative), 'utf8');

describe('EquiFlow branding', () => {
  it('no frontend source file or index.html uses the old product name', () => {
    const offenders = [...sourceFiles(srcDir), path.join(frontendDir, 'index.html')]
      .filter((file) => OLD_BRANDING.test(fs.readFileSync(file, 'utf8')))
      .map((file) => path.relative(frontendDir, file));
    assert.deepStrictEqual(offenders, [], `Old branding found in: ${offenders.join(', ')}`);
  });

  it('sets the browser tab title and metadata to EquiFlow', () => {
    const html = read('index.html');
    assert.ok(html.includes('<title>EquiFlow — Hospitality Workforce Operations</title>'), 'Tab title');
    assert.ok(html.includes('<meta name="application-name" content="EquiFlow" />'), 'Application name meta');
    assert.ok(html.includes('content="EquiFlow — Hospitality Workforce Operations. Balanced workloads. Smarter operations."'), 'Description meta');
  });

  it('shows the EquiFlow wordmark on every branded screen', () => {
    const wordmark = 'Equi<span className="text-emerald-600">Flow</span>';
    for (const file of [
      'src/pages/LoginPage.jsx',
      'src/pages/ForgotPasswordPage.jsx',
      'src/pages/SetCredentialsPage.jsx',
      'src/pages/EmailVerificationPage.jsx',
    ]) {
      assert.ok(read(file).includes(wordmark), `${file} must show the EquiFlow wordmark`);
    }
  });

  it('header and mobile sidebar share the same brand block proportions', () => {
    const appWordmark = 'Equi<span className="text-emerald-700">Flow</span>';
    const wordmarkText = 'text-[17px] font-bold tracking-tight text-slate-900 leading-5';
    for (const file of ['src/components/layout/Header.jsx', 'src/components/layout/Sidebar.jsx']) {
      const source = read(file);
      assert.ok(source.includes(appWordmark), `${file}: two-tone EquiFlow wordmark`);
      assert.ok(/<EquiFlowLogo className="w-9 h-9 shrink-0 rounded-\[28%\] shadow-sm/.test(source), `${file}: 36px Flow E logo`);
      assert.ok(source.includes(wordmarkText), `${file}: 17px bold wordmark`);
    }
    const header = read('src/components/layout/Header.jsx');
    assert.ok(
      header.includes('<span className="hidden sm:block text-[9px] font-semibold text-slate-500 tracking-[0.08em] uppercase leading-none mt-1">'),
      'Header subtitle: 9px muted blue-gray with subtle letter spacing, hidden below sm'
    );
    assert.ok(header.includes('<header className="h-16 '), 'Header height unchanged (64px)');
    assert.ok(read('src/components/layout/Sidebar.jsx').includes('h-12 px-2 flex items-center justify-between'), 'Drawer header height unchanged');
  });

  it('uses the Flow E logo in every branding location and nowhere uses the old building icon', () => {
    const authLogo = '<EquiFlowLogo className="w-14 h-14 rounded-[28%] shadow-lg shadow-emerald-600/25 mb-4 transform transition-transform hover:scale-105 duration-200" />';
    for (const file of ['src/pages/LoginPage.jsx', 'src/pages/ForgotPasswordPage.jsx', 'src/pages/SetCredentialsPage.jsx', 'src/pages/EmailVerificationPage.jsx']) {
      assert.ok(read(file).includes(authLogo), `${file}: 56px Flow E logo`);
    }
    const offenders = [...sourceFiles(srcDir), path.join(frontendDir, 'index.html')]
      .filter((file) => /HotelLogoIcon|vite\.svg/.test(fs.readFileSync(file, 'utf8')))
      .map((file) => path.relative(frontendDir, file));
    assert.deepStrictEqual(offenders, [], `Old logo / favicon references in: ${offenders.join(', ')}`);
  });

  it('favicon is the same Flow E artwork as the React logo component', () => {
    assert.ok(read('index.html').includes('<link rel="icon" type="image/svg+xml" href="/equiflow-logo.svg" />'), 'Favicon link');
    const favicon = read('public/equiflow-logo.svg');
    const component = read('src/components/brand/EquiFlowLogo.jsx');
    const constant = (name) => component.match(new RegExp(`${name}: '([^']+)'`))?.[1];
    for (const name of ['bodyPath', 'middlePath', 'gradientFrom', 'gradientTo', 'mint']) {
      assert.ok(constant(name) && favicon.includes(constant(name)), `favicon must use the component's ${name}`);
    }
    assert.ok(favicon.includes('viewBox="0 0 48 48"') && favicon.includes('rx="13.5"') && favicon.includes('stroke-width="4.5"'), 'Same geometry');
    assert.ok(favicon.indexOf(constant('middlePath')) < favicon.indexOf(constant('bodyPath')), 'Mint arm is drawn under the white spine, as in the component');
  });

  it('uses the EquiFlow name, description and footer consistently', () => {
    const footer = 'EquiFlow — Hospitality Workforce Operations &copy; {new Date().getFullYear()} • All rights reserved';
    assert.ok(read('src/pages/LoginPage.jsx').includes('Sign in to your EquiFlow account'), 'Login subtitle');
    for (const [file, count] of [['src/pages/LoginPage.jsx', 1], ['src/pages/SetCredentialsPage.jsx', 2], ['src/pages/EmailVerificationPage.jsx', 2]]) {
      assert.strictEqual(read(file).split(footer).length - 1, count, `${file} footer(s)`);
    }
    assert.ok(read('src/components/layout/Header.jsx').includes('title="EquiFlow — Hospitality Workforce Operations"'), 'Header logo title');
    assert.ok(read('src/pages/ProfilePage.jsx').includes('EquiFlow — Hospitality Workforce Operations'), 'Profile description');
    assert.ok(read('src/components/common/DashboardPlaceholder.jsx').includes('Balanced workloads. Smarter operations.'), 'Tagline');
    assert.strictEqual(JSON.parse(read('package.json')).description, 'EquiFlow — Hospitality Workforce Operations', 'package.json description');
  });
});
