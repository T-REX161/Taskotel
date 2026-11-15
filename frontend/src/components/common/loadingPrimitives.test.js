import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement as h } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Spinner, { SPINNER_SIZES } from './Spinner.js';
import Skeleton, { SKELETON_TONES } from './Skeleton.js';
import LoadError from './LoadError.js';

// Stage 5C: the shared Spinner, Skeleton and LoadError, rendered with React (react-dom/server) and inspected as elements.
const render = (type, props) => renderToStaticMarkup(h(type, props));
const read = (path) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const OLD_ARC = 'M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z';
// all buttons in a rendered element tree (LoadError is a plain function component, so calling it returns its elements)
const buttonsIn = (element) => {
  const found = [];
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) { node.forEach(walk); return; }
    if (node.type === 'button') found.push(node);
    walk(node.props?.children);
  };
  walk(element);
  return found;
};

describe('Spinner', () => {
  it('draws the same graphic as the copies it replaced, hidden from assistive technology', () => {
    const html = render(Spinner, {});
    assert.ok(html.startsWith('<svg class="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24" aria-hidden="true" focusable="false"'));
    assert.ok(html.includes('<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>'));
    assert.ok(html.includes(`<path class="opacity-75" fill="currentColor" d="${OLD_ARC}"></path>`));
    assert.doesNotMatch(html, /role=|aria-live|aria-label|<title/, 'no announcement of its own: the text beside it is what is read');
  });

  it('sizes and extra classes reproduce the old ones exactly', () => {
    assert.deepEqual(SPINNER_SIZES, { xs: 'h-3.5 w-3.5', sm: 'h-4 w-4', md: 'h-7 w-7', lg: 'h-8 w-8' });
    const cls = (props) => render(Spinner, props).match(/class="([^"]*)"/)[1];
    assert.equal(cls({ className: 'text-white' }), 'animate-spin h-4 w-4 text-white', 'button spinners');
    assert.equal(cls({ size: 'lg', className: 'text-emerald-600' }), 'animate-spin h-8 w-8 text-emerald-600', 'page loaders');
    assert.equal(cls({ size: 'md', className: 'text-emerald-600' }), 'animate-spin h-7 w-7 text-emerald-600', 'Staff page loader');
    assert.equal(cls({ size: 'xs', className: 'shrink-0' }), 'animate-spin h-3.5 w-3.5 shrink-0', 'username check');
    assert.equal(cls({ size: 'huge' }), 'animate-spin h-4 w-4', 'an unknown size falls back to the default');
  });
});

describe('Skeleton', () => {
  it('a decorative placeholder: hidden from assistive technology, shaped by its classes', () => {
    assert.equal(render(Skeleton, { className: 'h-11 rounded-xl' }), '<div class="animate-pulse bg-gray-200 h-11 rounded-xl" aria-hidden="true" data-skeleton=""></div>');
    assert.equal(render(Skeleton, { tone: 'soft', className: 'h-3 w-3/4 rounded' }), '<div class="animate-pulse bg-gray-100 h-3 w-3/4 rounded" aria-hidden="true" data-skeleton=""></div>');
    assert.ok(render(Skeleton, { as: 'span' }).startsWith('<span '));
    assert.deepEqual(SKELETON_TONES, { strong: 'bg-gray-200', soft: 'bg-gray-100' });
  });

  it('follows the motion convention: Tailwind\'s loading pulse, which the reduced-motion rules leave running (as for spinners)', () => {
    const css = read('index.css');
    const rules = [...css.matchAll(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/g)].map((m) => m[1]).join('\n');
    assert.ok(rules.length > 0);
    assert.doesNotMatch(rules, /animate-pulse|animate-spin/, 'functional loading animations are not switched off');
  });

  it('the pages that had hand-made placeholders use it, inside a region that says what is loading', () => {
    const users = read('pages/UsersPage.jsx');
    assert.ok(users.includes('<div className="w-full space-y-6" aria-busy="true">'));
    assert.equal((users.match(/<Skeleton className="h-11 rounded-xl/g) || []).length, 3);
    assert.ok(users.includes('<Skeleton className="h-80 rounded-xl" />') && users.includes('<p role="status" className="sr-only">Loading users…</p>'));
    const settings = read('pages/SettingsPage.jsx');
    assert.ok(settings.includes('<Skeleton className="h-3.5 w-40 rounded" /><Skeleton tone="soft" className="h-3 w-3/4 rounded" />'));
    assert.ok(settings.includes('<Skeleton className="h-6 w-11 rounded-full" />'));
    assert.ok(settings.includes('{loading && <p role="status" className="sr-only">{SYSTEM_SETTINGS_MESSAGES.loading}</p>}'));
  });
});

describe('LoadError', () => {
  it('panel: one alert with the title, the message and a Retry wired to the callback (same look as the page blocks it replaced)', () => {
    const retry = () => {};
    const element = LoadError({ title: 'Failed to load tasks', message: 'Network error', onRetry: retry });
    const html = renderToStaticMarkup(element);
    assert.equal(html,
      '<div role="alert" class="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3"><h3 class="font-bold text-base">Failed to load tasks</h3>'
      + '<p class="text-sm">Network error</p><button type="button" class="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer disabled:cursor-not-allowed disabled:opacity-60">Retry</button></div>');
    const [button] = buttonsIn(element);
    assert.equal(button.props.onClick, retry, 'the page\'s own retry callback, unchanged');
    assert.equal(renderToStaticMarkup(LoadError({ message: 'x', onRetry: retry, retryLabel: 'Try Again' })).includes('>Try Again</button>'), true, 'each page keeps its label');
  });

  it('without a retry it is still a complete message, with no dead button', () => {
    const html = render(LoadError, { title: 'Failed to load departments', message: 'Server unavailable' });
    assert.ok(html.includes('role="alert"') && html.includes('Failed to load departments') && html.includes('Server unavailable'));
    assert.doesNotMatch(html, /<button/);
    assert.doesNotMatch(render(LoadError, { message: 'x', onRetry: 'not a function' }), /<button/);
  });

  it('banner: the message and an outlined Retry on one line; compact text, disabled retry and a test id when asked', () => {
    const retry = () => {};
    const html = render(LoadError, { variant: 'banner', compact: true, message: 'Staff members could not be loaded.', onRetry: retry, retryDisabled: true, testId: 'x' });
    assert.ok(html.startsWith('<div role="alert" data-testid="x" class="p-4 rounded-xl bg-red-50 border border-red-200 text-red-800 flex flex-wrap items-center justify-between gap-3 text-xs">'));
    assert.ok(html.includes('<span class="font-medium">Staff members could not be loaded.</span>'));
    assert.match(html, /<button type="button" disabled="" class="px-3 py-1\.5 rounded-lg border border-red-300 bg-white text-red-700 text-xs font-semibold[^"]*">Retry<\/button>/);
    assert.ok(render(LoadError, { variant: 'banner', message: 'x' }).includes('justify-between gap-3 text-sm"'), 'normal size by default');
  });

  it('a single live region: exactly one role="alert", and nothing inside it announces on its own', () => {
    for (const props of [{ title: 'T', message: 'M', onRetry: () => {} }, { variant: 'banner', message: 'M', onRetry: () => {} }]) {
      const html = render(LoadError, props);
      assert.equal((html.match(/role="/g) || []).length, 1);
      assert.doesNotMatch(html, /aria-live/);
    }
  });
});

describe('where they are used', () => {
  const PAGE_ERRORS = {
    'pages/TasksPage.jsx': '<LoadError title="Failed to load tasks" message={error} onRetry={() => fetchTasksData(true)} retryLabel="Retry" />',
    'pages/AssignmentsPage.jsx': '<LoadError title="Failed to load assignments" message={error} onRetry={fetchData} retryLabel="Try Again" />',
    'pages/WorkloadPage.jsx': '<LoadError title="Failed to load staff workload" message={error} onRetry={() => fetchData()} retryLabel="Try Again" />',
    'pages/DepartmentsPage.jsx': '<LoadError title="Failed to load departments" message={error} onRetry={() => fetchDepartments()} retryLabel="Retry" />',
    'pages/Dashboard.jsx': '<LoadError title="Failed to load dashboard" message={error} onRetry={() => fetchDashboardData()} retryLabel="Try Again" />',
  };

  it('page error panels keep their own title, retry callback and button label', () => {
    for (const [file, line] of Object.entries(PAGE_ERRORS)) assert.ok(read(file).includes(line), file);
  });

  it('page loaders: the spinner beside its text, now one status region (no visual change)', () => {
    for (const file of ['pages/TasksPage.jsx', 'pages/AssignmentsPage.jsx', 'pages/WorkloadPage.jsx', 'pages/DepartmentsPage.jsx', 'pages/Dashboard.jsx']) {
      assert.match(read(file), /<div role="status" className="flex flex-col items-center gap-3">\s*<Spinner size="lg" className="text-emerald-600" \/>/, file);
    }
    assert.match(read('pages/StaffPage.jsx'), /<div role="status" className="flex flex-col items-center gap-3">\s*<Spinner size="md" className="text-emerald-600" \/>/);
  });

  it('error UIs that look or behave differently are left as they were (no forced reuse)', () => {
    assert.ok(read('pages/StaffPage.jsx').includes('p-5 bg-rose-50 border border-rose-200 rounded-xl'), 'Staff: its own compact rose panel');
    assert.ok(read('pages/UsersPage.jsx').includes('<div role="alert" className="bg-red-50 text-red-800 p-4 rounded-xl flex justify-between'), 'Users: a one-line bar');
    assert.ok(read('components/tasks/CreateTaskForm.jsx').includes("{staffState === 'error' && ("), 'Create Task: an inline hint under its field');
    assert.ok(read('components/settings/PersonalPreferencesCard.jsx').includes('data-testid="preferences-load-error"'), 'Preferences: inline in the status line');
  });
});
