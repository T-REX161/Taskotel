import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { DURATIONS, EASINGS, KEYFRAMES, ANIMATIONS, EXIT_MS, motionVariables } = require('./motion.cjs');
const config = require('../../tailwind.config.cjs');
const css = fs.readFileSync(new URL('../index.css', import.meta.url), 'utf8');

// ── tokens ─────────────────────────────────────────────────────────────────────────────────────────────────────

test('durations: fast 120ms, base 160ms, moderate 200ms, slow 250ms', () => {
  assert.deepEqual(DURATIONS, { fast: '120ms', base: '160ms', moderate: '200ms', slow: '250ms' });
});

test('easings: enter decelerates, exit accelerates, standard for state changes; none overshoots', () => {
  assert.deepEqual(EASINGS, {
    enter: 'cubic-bezier(0, 0, 0.2, 1)',
    exit: 'cubic-bezier(0.4, 0, 1, 1)',
    standard: 'cubic-bezier(0.2, 0, 0, 1)',
  });
  for (const value of Object.values(EASINGS)) {
    const points = value.match(/[\d.]+/g).map(Number);
    assert.ok(points.every((p) => p >= 0 && p <= 1), `${value}: no bounce or overshoot`);
  }
});

test('keyframes: small, restrained movements only (fade; scale from 0.98; 2px menu; 4px notification)', () => {
  assert.deepEqual(Object.keys(KEYFRAMES).sort(), [
    'tk-dialog-in', 'tk-dialog-out', 'tk-fade-in', 'tk-fade-out', 'tk-menu-in', 'tk-menu-out', 'tk-toast-in', 'tk-toast-out',
  ]);
  assert.deepEqual(KEYFRAMES['tk-fade-in'], { from: { opacity: '0' }, to: { opacity: '1' } });
  assert.deepEqual(KEYFRAMES['tk-fade-out'], { from: { opacity: '1' }, to: { opacity: '0' } });
  assert.equal(KEYFRAMES['tk-dialog-in'].from.transform, 'scale(0.98)');
  assert.equal(KEYFRAMES['tk-menu-in'].from.transform, 'translateY(-2px)');
  assert.equal(KEYFRAMES['tk-toast-in'].from.transform, 'translateY(-4px)');
  for (const [name, frames] of Object.entries(KEYFRAMES)) {
    const exit = name.endsWith('-out');
    assert.equal(frames.to.opacity, exit ? '0' : '1', `${name} ends ${exit ? 'hidden' : 'fully visible'}`);
  }
});

test('exit keyframes: each entrance played backwards, ending where its entrance starts', () => {
  for (const kind of ['dialog', 'menu', 'toast']) {
    assert.deepEqual(KEYFRAMES[`tk-${kind}-out`], { from: KEYFRAMES[`tk-${kind}-in`].to, to: KEYFRAMES[`tk-${kind}-in`].from }, kind);
  }
});

test('animations: each plays once with the shared duration and easing', () => {
  assert.deepEqual(ANIMATIONS, {
    'fade-in': 'tk-fade-in 160ms cubic-bezier(0, 0, 0.2, 1) both',
    'fade-out': 'tk-fade-out 120ms cubic-bezier(0.4, 0, 1, 1) both',
    'dialog-in': 'tk-dialog-in 200ms cubic-bezier(0, 0, 0.2, 1) both',
    'dialog-out': 'tk-dialog-out 120ms cubic-bezier(0.4, 0, 1, 1) both',
    'menu-in': 'tk-menu-in 160ms cubic-bezier(0, 0, 0.2, 1) both',
    'menu-out': 'tk-menu-out 120ms cubic-bezier(0.4, 0, 1, 1) both',
    'toast-in': 'tk-toast-in 160ms cubic-bezier(0, 0, 0.2, 1) both',
    'toast-out': 'tk-toast-out 120ms cubic-bezier(0.4, 0, 1, 1) both',
  });
  assert.equal(EXIT_MS, 120, 'every exit is the fast duration: shorter than its entrance, so closing never holds anything up');
  for (const value of Object.values(ANIMATIONS)) assert.doesNotMatch(value, /infinite|alternate/);
  assert.ok(!('fadeIn' in ANIMATIONS), 'there is no camel-case fadeIn animation: components use animate-fade-in');
});

// ── wiring ─────────────────────────────────────────────────────────────────────────────────────────────────────

test('Tailwind config: the tokens become utilities and CSS variables, alongside the existing theme', () => {
  const extend = config.theme.extend;
  assert.equal(extend.transitionDuration, DURATIONS);
  assert.equal(extend.transitionTimingFunction, EASINGS);
  assert.equal(extend.keyframes, KEYFRAMES);
  assert.equal(extend.animation, ANIMATIONS);
  assert.ok(extend.colors.surface, 'existing theme extension kept');
  assert.deepEqual(motionVariables(), {
    '--tk-duration-fast': '120ms', '--tk-duration-base': '160ms', '--tk-duration-moderate': '200ms', '--tk-duration-slow': '250ms',
    '--tk-ease-enter': 'cubic-bezier(0, 0, 0.2, 1)', '--tk-ease-exit': 'cubic-bezier(0.4, 0, 1, 1)', '--tk-ease-standard': 'cubic-bezier(0.2, 0, 0, 1)',
  });
});

test('Tailwind generates the new utilities and leaves the built-in loading animations unchanged', async () => {
  const postcss = (await import('postcss')).default;
  const tailwind = require('tailwindcss');
  const sample = '<div class="animate-fade-in animate-fade-out animate-dialog-in animate-menu-in animate-toast-in animate-dialog-out animate-menu-out animate-toast-out duration-fast duration-base duration-moderate duration-slow ease-enter ease-exit ease-standard animate-spin animate-pulse"></div>';
  const result = await postcss([tailwind({ ...config, content: [{ raw: sample }] })]).process('@tailwind base;\n@tailwind utilities;', { from: undefined });
  const out = result.css.replace(/\s+/g, ' ');
  assert.match(out, /\.animate-dialog-in \{ animation: tk-dialog-in 200ms cubic-bezier\(0, 0, 0\.2, 1\) both/);
  assert.match(out, /@keyframes tk-dialog-in \{ from \{ opacity: 0; transform: scale\(0\.98\);? \}/);
  assert.match(out, /\.animate-menu-in \{ animation: tk-menu-in 160ms/);
  assert.match(out, /\.animate-toast-in \{ animation: tk-toast-in 160ms/);
  assert.match(out, /\.animate-dialog-out \{ animation: tk-dialog-out 120ms cubic-bezier\(0\.4, 0, 1, 1\) both/);
  assert.match(out, /\.animate-menu-out \{ animation: tk-menu-out 120ms/);
  assert.match(out, /\.animate-toast-out \{ animation: tk-toast-out 120ms/);
  assert.match(out, /@keyframes tk-dialog-out \{ from \{ opacity: 1; transform: scale\(1\);? \} to \{ opacity: 0; transform: scale\(0\.98\);? \}/);
  assert.match(out, /\.duration-fast \{ transition-duration: 120ms/);
  assert.match(out, /\.ease-enter \{ transition-timing-function: cubic-bezier\(0, 0, 0\.2, 1\)/);
  assert.match(out, /--tk-duration-moderate: 200ms/);
  assert.match(out, /\.animate-spin \{ animation: spin 1s linear infinite/, 'functional spinner unchanged');
  assert.match(out, /\.animate-pulse \{ animation: pulse 2s/, 'loading skeleton pulse unchanged');
});

// ── reduced motion ─────────────────────────────────────────────────────────────────────────────────────────────

const reducedBlocks = [...css.matchAll(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/g)].map((m) => m[1]);
const motionBlock = css.slice(css.indexOf('Motion: reduced-motion strategy'));

test('reduced motion: the shared entrance and exit animations do not play, and transitions become near-instant', () => {
  assert.match(motionBlock, /\.animate-fade-in,\s*\.animate-fade-out,\s*\.animate-dialog-in,\s*\.animate-menu-in,\s*\.animate-toast-in,\s*\.animate-dialog-out,\s*\.animate-menu-out,\s*\.animate-toast-out \{\s*animation: none;/);
  assert.match(motionBlock, /\*,\s*\*::before,\s*\*::after \{\s*transition-duration: 1ms !important;\s*transition-delay: 0s !important;/);
});

test('reduced motion: functional loading animations are never switched off', () => {
  // no reduced-motion rule sets `animation` on every element or names the spinner / skeleton classes
  for (const block of reducedBlocks) {
    assert.doesNotMatch(block, /animate-spin|animate-pulse/);
    for (const rule of block.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
      const [selectors, body] = [rule[1].trim(), rule[2]];
      if (/(^|,)\s*\*/.test(selectors)) assert.doesNotMatch(body, /animation/, `universal rule must not touch animations: ${selectors}`);
    }
  }
});

test('reduced motion: the existing theme switch, clock and login rules are unchanged', () => {
  assert.ok(css.includes('html.theme-switching::view-transition-old(root),\n  html.theme-switching::view-transition-new(root) {\n    animation: none;'));
  assert.ok(css.includes('.clock-roll-in, .clock-fade-in { animation: none; }') && css.includes('.clock-roll-out { display: none; }'));
  assert.ok(css.includes('--theme-transition-duration: 300ms;') && css.includes('--theme-reveal-duration: 900ms;'), 'theme timing untouched');
  assert.ok(fs.readFileSync(new URL('../pages/LoginPage.css', import.meta.url), 'utf8').includes('prefers-reduced-motion: reduce'));
});
