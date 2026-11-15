import { useLayoutEffect, useRef } from 'react';
import { prefersReducedMotion } from './presence.js';

/*
 * Page navigation feedback (Stage 4 of the motion plan): when the screen inside the app shell changes, its content fades in once
 * (160ms, opacity only). Entrance only: nothing waits for it, nothing is remounted, and the content takes clicks and keys throughout.
 *
 * It uses the Web Animations API on the existing content wrapper, so there is no class to add or remove, no forced reflow and no
 * timer: a new navigation cancels the previous fade, and a finished fade leaves no style behind. No JSX, so Node tests import it.
 */

/** DURATIONS.base and EASINGS.enter in motion.cjs (checked by pageTransition.test.js). */
export const PAGE_FADE_MS = 160;
export const PAGE_FADE_EASING = 'cubic-bezier(0, 0, 0.2, 1)';
export const PAGE_FADE_KEYFRAMES = [{ opacity: 0 }, { opacity: 1 }];

/** One key per screen: case, trailing slashes and the two dashboard paths ("/" and "/dashboard") do not count as a change. */
export function pageKey(pathname) {
  const path = String(pathname || '/').toLowerCase().replace(/\/+$/, '');
  return path === '' ? '/dashboard' : path;
}

/**
 * Whether a route change fades in. Never on the first render (initial load, refresh, arriving from sign-in), never for the same
 * screen (state-only or same-path replace navigations), never with reduced motion, and never when the navigation carries state:
 * such state can open a dialog as the page mounts, and the fading content must not hold it in its stacking context.
 */
export function shouldAnimatePage({ previousKey, nextKey, hasState, reduced }) {
  return previousKey !== null && previousKey !== nextKey && !hasState && !reduced;
}

/**
 * Returns a ref for the page content wrapper; the content fades in when `location` moves to another screen.
 * Any pointer or key press inside the content ends the fade at once, so a dialog the user opens is never rendered inside a
 * semi-transparent layer (which would sit below the sticky header until the fade ended).
 */
export function createPageTransitionHook(hooks) {
  return function usePageTransition(location) {
    const ref = hooks.useRef(null);
    const previousKey = hooks.useRef(null);
    const key = pageKey(location.pathname);
    const hasState = location.state != null;

    hooks.useLayoutEffect(() => {
      const previous = previousKey.current;
      previousKey.current = key; // StrictMode's second run then sees the same key and does nothing
      const element = ref.current;
      if (!element || typeof element.animate !== 'function') return undefined;
      if (!shouldAnimatePage({ previousKey: previous, nextKey: key, hasState, reduced: prefersReducedMotion() })) return undefined;

      const animation = element.animate(PAGE_FADE_KEYFRAMES, { duration: PAGE_FADE_MS, easing: PAGE_FADE_EASING });
      const finish = () => animation.finish();
      element.addEventListener('pointerdown', finish, { capture: true, once: true });
      element.addEventListener('keydown', finish, { capture: true, once: true });
      return () => {
        animation.cancel(); // the next navigation (or leaving the shell) never inherits a half-faded page
        element.removeEventListener('pointerdown', finish, { capture: true });
        element.removeEventListener('keydown', finish, { capture: true });
      };
    }, [key, hasState]);

    return ref;
  };
}

export const usePageTransition = createPageTransitionHook({ useLayoutEffect, useRef });
