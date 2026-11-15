import test, { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import {
  PAGE_FADE_EASING, PAGE_FADE_KEYFRAMES, PAGE_FADE_MS, createPageTransitionHook, pageKey, shouldAnimatePage,
} from './pageTransition.js';

// Stage 4 of the motion plan: a 160ms opacity-only fade when the screen inside the app shell changes.
const require = createRequire(import.meta.url);
const motion = require('./motion.cjs');
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\r\n/g, '\n');

// ── a stand-in for React's useRef / useLayoutEffect, and an element that records its animations ───────────────────────
function fakeElement() {
  const listeners = new Map();
  const animations = [];
  return {
    animations,
    listeners,
    animate(keyframes, options) {
      const animation = { keyframes, options, state: 'running', cancel() { this.state = 'cancelled'; }, finish() { this.state = 'finished'; } };
      animations.push(animation);
      return animation;
    },
    addEventListener(type, fn) { listeners.set(type, fn); },
    removeEventListener(type, fn) { if (listeners.get(type) === fn) listeners.delete(type); },
    press(type) { const fn = listeners.get(type); listeners.delete(type); if (fn) fn(); }, // once: true
  };
}

function mountShell(initialLocation, { strict = false, element = fakeElement() } = {}) {
  const refs = [];
  let effect = null; // { deps, cleanup }
  let index = 0;
  let pendingRun = null;
  const hooks = {
    useRef(initial) { const i = index++; if (!(i in refs)) refs[i] = { current: initial }; return refs[i]; },
    useLayoutEffect(fn, deps) {
      index++;
      if (effect && deps.every((d, k) => Object.is(d, effect.deps[k]))) return;
      pendingRun = { fn, deps };
    },
  };
  const runPageTransition = createPageTransitionHook(hooks); // called by the stand-in runtime below, not by React
  const commit = () => {
    if (!pendingRun) return;
    if (effect?.cleanup) effect.cleanup();
    effect = { fn: pendingRun.fn, deps: pendingRun.deps, cleanup: pendingRun.fn() };
    pendingRun = null;
  };
  const render = (location) => {
    index = 0;
    const ref = runPageTransition(location);
    ref.current = element; // React attaches the ref to the content wrapper before layout effects run
    commit();
  };
  render(initialLocation);
  if (strict) {
    // StrictMode in development: on mount, React cleans up each effect and runs the same effect again (refs are kept)
    if (effect.cleanup) effect.cleanup();
    effect.cleanup = effect.fn();
  }
  return { element, navigate: render, unmount: () => effect?.cleanup?.() };
}

const at = (pathname, state = null) => ({ pathname, state });
const withMotion = (reduce, fn) => {
  const original = globalThis.window;
  globalThis.window = { matchMedia: () => ({ matches: reduce }) };
  try { return fn(); } finally { if (original === undefined) delete globalThis.window; else globalThis.window = original; }
};

// ── the decision ──────────────────────────────────────────────────────────────────────────────────────────────────

test('pageKey: one key per screen; "/" and "/dashboard" are the same screen', () => {
  assert.equal(pageKey('/'), '/dashboard');
  assert.equal(pageKey('/dashboard'), '/dashboard');
  assert.equal(pageKey('/Dashboard/'), '/dashboard');
  assert.equal(pageKey('/tasks/'), '/tasks');
  assert.equal(pageKey('/TASKS'), '/tasks');
  assert.notEqual(pageKey('/tasks'), pageKey('/tasks/create'));
  assert.notEqual(pageKey('/settings'), pageKey('/settings/system'));
  assert.equal(pageKey(''), '/dashboard');
  assert.equal(pageKey(undefined), '/dashboard');
});

test('shouldAnimatePage: only a real change of screen, without navigation state, with motion allowed', () => {
  const base = { previousKey: '/tasks', nextKey: '/staff', hasState: false, reduced: false };
  assert.equal(shouldAnimatePage(base), true);
  assert.equal(shouldAnimatePage({ ...base, previousKey: null }), false, 'first render: initial load, refresh, arriving from sign-in');
  assert.equal(shouldAnimatePage({ ...base, nextKey: '/tasks' }), false, 'same screen');
  assert.equal(shouldAnimatePage({ ...base, hasState: true }), false, 'state can open a dialog as the page mounts');
  assert.equal(shouldAnimatePage({ ...base, reduced: true }), false, 'reduced motion');
});

test('the fade: 160ms, opacity only, the shared base duration and entrance easing; nothing kept afterwards', () => {
  assert.equal(`${PAGE_FADE_MS}ms`, motion.DURATIONS.base);
  assert.equal(PAGE_FADE_EASING, motion.EASINGS.enter);
  assert.deepEqual(PAGE_FADE_KEYFRAMES, [{ opacity: 0 }, { opacity: 1 }]);
  const element = fakeElement();
  withMotion(false, () => { const shell = mountShell(at('/tasks'), { element }); shell.navigate(at('/staff')); });
  const [{ options, keyframes }] = element.animations;
  assert.deepEqual(options, { duration: 160, easing: 'cubic-bezier(0, 0, 0.2, 1)' }, 'no delay, no fill: a finished fade leaves no style (or stacking context) behind');
  for (const frame of keyframes) assert.deepEqual(Object.keys(frame), ['opacity'], 'no transform: fixed overlays inside the page keep the window as their frame');
});

// ── the hook ──────────────────────────────────────────────────────────────────────────────────────────────────────

describe('usePageTransition', () => {
  it('does not animate on mount, including StrictMode\'s second effect run', () => {
    withMotion(false, () => {
      assert.equal(mountShell(at('/tasks')).element.animations.length, 0);
      assert.equal(mountShell(at('/tasks'), { strict: true }).element.animations.length, 0);
    });
  });

  it('fades in once per change of screen, and back/forward is just another change', () => {
    withMotion(false, () => {
      const shell = mountShell(at('/dashboard'), { strict: true });
      shell.navigate(at('/tasks'));
      shell.navigate(at('/tasks')); // re-render on the same screen
      shell.navigate(at('/dashboard')); // back
      assert.equal(shell.element.animations.length, 2);
    });
  });

  it('"/" ↔ "/dashboard", and same-path state changes, never animate', () => {
    withMotion(false, () => {
      const shell = mountShell(at('/'));
      shell.navigate(at('/dashboard'));
      shell.navigate(at('/'));
      assert.equal(shell.element.animations.length, 0);
      const assignments = mountShell(at('/tasks'));
      assignments.navigate(at('/assignments', { taskId: 'x' })); // a navigation that opens a dialog from its state
      assignments.navigate(at('/assignments', null)); // the page then clears that state with a same-path replace
      assert.equal(assignments.element.animations.length, 0, 'skipped for state navigation; the replace is not a new screen');
    });
  });

  it('rapid navigation: each new screen cancels the previous fade, so no page is left half-faded', () => {
    withMotion(false, () => {
      const shell = mountShell(at('/dashboard'));
      for (const path of ['/tasks', '/staff', '/assignments', '/workload', '/departments']) shell.navigate(at(path));
      const states = shell.element.animations.map((a) => a.state);
      assert.deepEqual(states, ['cancelled', 'cancelled', 'cancelled', 'cancelled', 'running']);
      shell.unmount();
      assert.equal(shell.element.animations.at(-1).state, 'cancelled', 'leaving the shell cleans up too');
      assert.equal(shell.element.listeners.size, 0, 'no listener is left behind');
    });
  });

  it('a click or key press in the page ends the fade at once (a dialog opened then is never inside a faded layer)', () => {
    withMotion(false, () => {
      const shell = mountShell(at('/dashboard'));
      shell.navigate(at('/tasks'));
      assert.deepEqual([...shell.element.listeners.keys()].sort(), ['keydown', 'pointerdown']);
      shell.element.press('pointerdown');
      assert.equal(shell.element.animations[0].state, 'finished');
      shell.navigate(at('/staff'));
      shell.element.press('keydown');
      assert.equal(shell.element.animations[1].state, 'finished');
    });
  });

  it('reduced motion: no animation at all, so navigation looks exactly as before', () => {
    withMotion(true, () => {
      const shell = mountShell(at('/dashboard'));
      for (const path of ['/tasks', '/staff', '/dashboard']) shell.navigate(at(path));
      assert.equal(shell.element.animations.length, 0);
      assert.equal(shell.element.listeners.size, 0);
    });
  });

  it('an element without the Web Animations API is simply not animated', () => {
    withMotion(false, () => {
      const element = { addEventListener() {}, removeEventListener() {} };
      const shell = mountShell(at('/dashboard'), { element });
      assert.doesNotThrow(() => shell.navigate(at('/tasks')));
    });
  });
});

// ── wiring: nothing else about routing changed ───────────────────────────────────────────────────────────────────

describe('AppShell', () => {
  const shell = read('../components/layout/AppShell.jsx');

  it('the fade is on the existing content wrapper, which is still keyed by contentKey only (no remount per route)', () => {
    assert.ok(shell.includes('const contentRef = usePageTransition(location);'));
    assert.ok(shell.includes('<div key={contentKey} ref={contentRef} className="max-w-[1440px] mx-auto w-full">'));
    assert.ok(shell.includes("const contentKey = currentNav === 'Settings' ? 'settings' : `${dateFormat}|${timeFormat}`;"), 'contentKey unchanged');
    assert.ok(shell.includes('{children || <Outlet />}') && !/<Outlet key=/.test(shell), 'the outlet is not keyed');
  });

  it('scroll handling is unchanged, and navigation does not use view transitions', () => {
    assert.ok(shell.includes("if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual';"));
    assert.match(shell, /useEffect\(\(\) => \{\s*window\.scrollTo\(0, 0\);\s*mainRef\.current\?\.scrollTo\?\.\(0, 0\);\s*\}, \[location\.pathname\]\);/);
    assert.doesNotMatch(shell, /startViewTransition|viewTransition/);
    assert.doesNotMatch(read('../App.jsx'), /viewTransition|AnimatePresence/);
  });

  it('the sign-in pages are outside the shell, so they get no page fade', () => {
    const app = read('../App.jsx');
    for (const path of ['/login', '/forgot-password', '/email-verification', '/set-credentials']) {
      assert.ok(app.indexOf(`path="${path}"`) < app.indexOf('<AppShell />'), path);
    }
  });

  it('the Workload page no longer fades by itself (the shared page fade replaces it)', () => {
    assert.ok(!read('../pages/WorkloadPage.jsx').includes('space-y-4 animate-fade-in'));
  });
});
