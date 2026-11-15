import { cloneElement, createElement, useEffect, useState } from 'react';

/*
 * Exit animations for modals, menus and temporary notices (Stage 3 of the motion plan; tokens in ./motion.cjs).
 *
 * React removes an element as soon as its condition turns false, which leaves no time for an exit animation. usePresence keeps
 * the last shown value for one short exit (EXIT_MS) and reports `closing` meanwhile; then it lets go, so the element unmounts.
 * While closing, an element must not take input: the *Motion helpers below add pointer-events-none, and callers add `inert`,
 * so nothing invisible can block a click or hold keyboard focus. With reduced motion the exit lasts 0ms: the element is removed
 * in the same render, exactly as before Stage 3.
 *
 * No JSX here, so the Node tests can import this file directly.
 */

/** Matches DURATIONS.fast in motion.cjs (checked by presence.test.js): the length of every exit animation. */
export const EXIT_MS = 120;

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** True when the user asked the system for reduced motion. False where it cannot be read (tests, old browsers). */
export function prefersReducedMotion() {
  try {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(REDUCED_MOTION_QUERY).matches;
  } catch {
    return false;
  }
}

/** How long a closing element stays mounted: the exit animation's length, or nothing at all with reduced motion. */
export function exitDuration(reduced) {
  return reduced ? 0 : EXIT_MS;
}

/**
 * What to render for `value` (anything truthy = shown) given the value kept from before:
 * shown → { present, value, closing: false }; just hidden → the kept value, closing; gone → nothing.
 */
export function presenceOf(value, retained) {
  if (value) return { present: true, value, closing: false };
  if (retained) return { present: true, value: retained, closing: true };
  return { present: false, value: null, closing: false };
}

/**
 * Builds usePresence from the two React hooks it needs (the tests pass a small stand-in runtime to step through time).
 *
 * usePresence(value, exitMs?) keeps `value` around for its exit animation after it becomes falsy.
 * Returns { present, value, closing }: render while `present`, using `value` (the last shown one while closing).
 * Showing a value again while it is closing cancels the exit and plays the entrance again.
 */
export function createPresenceHook(hooks) {
  return function usePresence(value, exitMs = exitDuration(prefersReducedMotion())) {
    const [retained, setRetained] = hooks.useState(value || null);
    // Remember the latest shown value during render (React re-runs this component at once, before its children render).
    if (value && value !== retained) setRetained(value);
    // Reduced motion (or no exit): forget it in the same render, so the element unmounts immediately.
    if (!value && retained && exitMs <= 0) setRetained(null);

    const state = presenceOf(value, exitMs > 0 ? retained : null);
    const { closing } = state;
    hooks.useEffect(() => {
      if (!closing) return undefined;
      const timer = setTimeout(() => setRetained(null), exitMs);
      return () => clearTimeout(timer);
    }, [closing, exitMs]);
    return state;
  };
}

export const usePresence = createPresenceHook({ useState, useEffect });

/**
 * For a modal that stays mounted and hides itself (`if (!isOpen) return null`): while it closes, render it once more with the
 * props it had when it was last open, plus `closing`. Otherwise it gets its real props, so it mounts, unmounts and keeps its
 * state exactly as before. `isShown(props)` must mirror the modal's own open check.
 */
export function withExitTransition(Component, isShown) {
  function ExitTransition(props) {
    const presence = usePresence(isShown(props) ? props : null);
    return createElement(Component, presence.closing ? { ...presence.value, closing: true } : props);
  }
  ExitTransition.displayName = `withExitTransition(${Component.displayName || Component.name || 'Component'})`;
  return ExitTransition;
}

/**
 * For an element its parent shows conditionally: `<Presence>{open && <Dialog ... />}</Presence>`. While it closes, the last
 * element is rendered again with `closing` added.
 */
export function Presence({ children }) {
  const presence = usePresence(children || null);
  if (!presence.present) return null;
  return presence.closing ? cloneElement(presence.value, { closing: true }) : presence.value;
}

// ── class names: one place for the entrance / exit pairs ──────────────────────────────────────────────────────

/** Modal backdrop: fades on its own; while closing it lets clicks through to the page at once. */
export const backdropMotion = (closing) => (closing ? 'animate-fade-out pointer-events-none' : 'animate-fade-in');

/** Modal panel: 200ms fade + scale from 0.98 in, 120ms out. */
export const dialogMotion = (closing) => (closing ? 'animate-dialog-out' : 'animate-dialog-in');

/** Dropdown / action menu: 160ms fade + 2px in, 120ms out. */
export const menuMotion = (closing) => (closing ? 'animate-menu-out pointer-events-none' : 'animate-menu-in');

/** Temporary notice: 160ms fade + 4px in, 120ms out. */
export const toastMotion = (closing) => (closing ? 'animate-toast-out pointer-events-none' : 'animate-toast-in');
