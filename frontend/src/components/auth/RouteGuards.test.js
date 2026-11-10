import { afterEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { AccessDenied, RequireRouteAccess, RequireSession, decideAccess, elementFor } from './RouteGuards.js';
import { ROUTE_ACCESS } from '../../utils/accessPolicy.js';

// The guards run for real (react-router + react-dom/server render, no browser). The project has no DOM environment, so a redirect cannot be
// followed here: a redirect is shown by the protected screen NOT being rendered, and its target is checked on the element the guard returns.

const b64 = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
const token = (exp = Math.floor(Date.now() / 1000) + 3600) => `${b64({ alg: 'HS256' })}.${b64({ sub: 'user', exp })}.sig`;

function stubStorage(initial) {
  const data = new Map(Object.entries(initial));
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: (k) => (data.has(k) ? data.get(k) : null), setItem: (k, v) => data.set(k, String(v)), removeItem: (k) => data.delete(k) },
  });
  return data;
}

// The application's route structure, with a marker in place of each screen
const SCREENS = [...ROUTE_ACCESS.map((rule) => rule.path), '*'];
const Shell = () => createElement('div', { 'data-shell': true }, createElement(Outlet));

function renderApp(pathname) {
  const screens = SCREENS.map((p) => createElement(Route, { key: p, path: p, element: createElement('p', null, `SCREEN ${p}`) }));
  return renderToStaticMarkup(
    createElement(
      MemoryRouter,
      { initialEntries: [pathname] },
      createElement(
        Routes,
        null,
        createElement(Route, { path: '/login', element: createElement('p', null, 'LOGIN PAGE') }),
        createElement(
          Route,
          { element: createElement(RequireSession) },
          createElement(Route, { element: createElement(Shell) }, createElement(Route, { element: createElement(RequireRouteAccess) }, ...screens)),
        ),
      ),
    ),
  );
}

const asRole = (role, extra = {}) => stubStorage({ token: token(), username: 'tester', role, ...extra });

afterEach(() => {
  delete globalThis.localStorage;
});

describe('unauthenticated and broken sessions', () => {
  it('a protected route renders nothing for a visitor with no stored sign-in', () => {
    stubStorage({});
    const html = renderApp('/staff');
    assert.ok(!html.includes('SCREEN') && !html.includes('data-shell'), html);
  });

  it('the redirect goes to /login, replacing the history entry, and remembers where the user was going', () => {
    const decision = decideAccess({ session: { status: 'anonymous', role: null }, pathname: '/staff' });
    assert.equal(decision.kind, 'login');
    const element = elementFor(decision, { pathname: '/staff' });
    assert.equal(element.type, Navigate);
    assert.equal(element.props.to, '/login');
    assert.equal(element.props.replace, true);
    assert.deepEqual(element.props.state, { from: { pathname: '/staff' } });
  });

  it('the login page itself is reachable without a sign-in (no redirect loop)', () => {
    stubStorage({});
    assert.ok(renderApp('/login').includes('LOGIN PAGE'));
  });

  it('an expired token is not shown the application, and the stale sign-in is cleared', () => {
    const data = stubStorage({ token: token(Math.floor(Date.now() / 1000) - 60), username: 'tester', role: 'ADMIN' });
    const html = renderApp('/dashboard');
    assert.ok(!html.includes('SCREEN') && !html.includes('data-shell'));
    assert.deepEqual([...data.keys()], [], 'token, username and role were removed');
  });

  it('a malformed token is treated the same way, and is cleared', () => {
    const data = stubStorage({ token: 'not-a-jwt', username: 'tester', role: 'ADMIN' });
    assert.ok(!renderApp('/dashboard').includes('SCREEN'));
    assert.equal(data.has('token'), false);
  });

  it('a token with no usable role (missing or unknown) does not open the application and does not become ADMIN', () => {
    for (const role of [undefined, '', 'admin', 'SUPERUSER']) {
      const stored = { token: token(), username: 'tester' };
      if (role !== undefined) stored.role = role;
      const data = stubStorage(stored);
      const html = renderApp('/profile');
      assert.ok(!html.includes('SCREEN') && !html.includes('data-shell'), `role ${String(role)}`);
      assert.equal(data.has('token'), false, 'the unusable sign-in is cleared');
    }
  });
});

describe('role access to screens', () => {
  it('ADMIN opens every screen, including Users', () => {
    asRole('ADMIN');
    for (const rule of ROUTE_ACCESS) assert.ok(renderApp(rule.path).includes(`SCREEN ${rule.path}`), rule.path);
  });

  it('MANAGER opens the management screens but is shown Access Denied on Users (the screen is not rendered)', () => {
    asRole('MANAGER');
    for (const p of ['/staff', '/tasks', '/tasks/create', '/assignments', '/workload', '/departments']) assert.ok(renderApp(p).includes(`SCREEN ${p}`), p);
    const html = renderApp('/users');
    assert.ok(html.includes('Access denied') && html.includes('Manager') && !html.includes('SCREEN /users'));
    assert.ok(html.includes('data-shell'), 'shown inside the application shell, so navigation is still there');
  });

  it('STAFF is refused every management screen with the Access Denied page', () => {
    asRole('STAFF');
    for (const p of ['/staff', '/tasks/create', '/assignments', '/departments', '/users']) {
      const html = renderApp(p);
      assert.ok(html.includes('Access denied') && !html.includes('SCREEN'), p);
    }
  });

  it('STAFF still opens their own screens', () => {
    asRole('STAFF');
    for (const p of ['/', '/dashboard', '/tasks', '/workload', '/profile']) assert.ok(renderApp(p).includes(`SCREEN ${p}`), p);
  });

  it('/settings is not a destination for any role: it falls through to the existing catch-all (the dashboard), with no redirect and no Access Denied', () => {
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) {
      asRole(role);
      const html = renderApp('/settings');
      assert.ok(html.includes('SCREEN *') && !html.includes('Access denied') && !html.includes('SCREEN /settings'), role);
    }
  });

  it('the Access Denied page links back to the dashboard, which every role may open (no loop)', () => {
    asRole('STAFF');
    const html = renderApp('/staff');
    assert.ok(html.includes('href="/dashboard"'));
    for (const role of ['ADMIN', 'MANAGER', 'STAFF']) {
      asRole(role);
      assert.ok(renderApp('/dashboard').includes('SCREEN /dashboard'), role);
    }
  });

  it('authorized deep links open directly, including a path below a screen and an unknown path (the dashboard)', () => {
    asRole('MANAGER');
    assert.ok(renderApp('/staff').includes('SCREEN /staff'));
    assert.ok(renderApp('/tasks/create').includes('SCREEN /tasks/create'));
    assert.ok(renderApp('/no-such-page').includes('SCREEN *'));
  });

  it('a variant spelling of an Admin path does not get around the rule', () => {
    asRole('MANAGER');
    for (const p of ['/USERS', '/users/']) assert.ok(renderApp(p).includes('Access denied'), p);
  });

  it('the Access Denied page names the role without assuming one', () => {
    assert.ok(renderToStaticMarkup(createElement(MemoryRouter, null, createElement(AccessDenied, { role: 'STAFF' }))).includes('(Staff)'));
    assert.ok(renderToStaticMarkup(createElement(MemoryRouter, null, createElement(AccessDenied, { role: null }))).includes('your role'));
  });
});

describe('the application actually uses the guards', () => {
  const read = (...p) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), ...p), 'utf8').replace(/\r\n/g, '\n');
  const app = read('..', '..', 'App.jsx');

  it('App nests RequireSession, then the shell, then RequireRouteAccess, around every protected screen; the public pages stay outside', () => {
    const order = ['<RequireSession />', '<AppShell />', '<RequireRouteAccess />', '<Route path="/dashboard"'].map((s) => app.indexOf(s));
    assert.ok(order.every((i) => i > 0) && [...order].sort((a, b) => a - b).join() === order.join(), String(order));
    for (const publicPath of ['/login', '/forgot-password', '/email-verification', '/set-credentials']) {
      assert.ok(app.indexOf(`path="${publicPath}"`) < app.indexOf('<RequireSession />'), publicPath);
    }
  });

  it('App has no /settings route and does not import the Settings page (the page file itself is kept)', () => {
    assert.ok(!app.includes('path="/settings"') && !app.includes('SettingsPage'));
    assert.ok(app.includes('<Route path="*" element={<Dashboard />} />'), 'the dashboard fallback is intact');
  });

  it('every screen in the access table has a route in App, so none is left unguarded or unreachable', () => {
    for (const rule of ROUTE_ACCESS) assert.ok(app.includes(`path="${rule.path}"`), rule.path);
  });
});
