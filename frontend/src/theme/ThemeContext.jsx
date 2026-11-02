import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import useCurrentUserProfile from '../hooks/useCurrentUserProfile';
import { preferencesLoaded, resolveTheme, savePreferences } from '../utils/preferences';

// The chosen theme is remembered per browser. index.html reads the same key before the first paint,
// so a page load or refresh never flashes the wrong theme.
export const THEME_STORAGE_KEY = 'taskotel-theme';
// The switch animation is defined in index.css (--theme-transition-duration / --theme-transition-easing).
// In the colour-transition fallback the helper class stays on a little longer than the animation, because on a
// large page the browser needs a moment to restyle every element before the colours start moving.
const TRANSITION_FALLBACK_MS = 300;
const TRANSITION_SLACK_MS = 250;

// The appearance preference is LIGHT, DARK or SYSTEM (the codes the server stores per user). The browser copy under
// THEME_STORAGE_KEY ('light' | 'dark' | 'system') is only a cache so the first paint is right; the signed-in user's saved
// preference (from their profile) is the source of truth and replaces the cache as soon as it loads.
const PREFERENCE_BY_STORED = { light: 'LIGHT', dark: 'DARK', system: 'SYSTEM' };

const systemPrefersDark = () => {
  try {
    return Boolean(window.matchMedia?.('(prefers-color-scheme: dark)').matches);
  } catch {
    return false;
  }
};

export function readStoredPreference() {
  try {
    return PREFERENCE_BY_STORED[localStorage.getItem(THEME_STORAGE_KEY)] || 'LIGHT';
  } catch {
    return 'LIGHT';
  }
}

/** The theme ('light' | 'dark') the stored preference gives right now. */
export function readStoredTheme() {
  return resolveTheme(readStoredPreference(), systemPrefersDark());
}

function storePreference(preference) {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, preference.toLowerCase());
  } catch {
    // Storage blocked (private mode): the theme still applies for this visit
  }
}

let transitionTimer;
let pendingTheme = null; // theme a running view transition is about to apply

const REVEAL_FALLBACK_MS = 900;
const REVEAL_FALLBACK_EASING = 'cubic-bezier(0.4, 0, 0.2, 1)';
// Width of the soft rim of the reveal circle: a small share of the distance it travels, within sensible limits
const featherFor = (radius) => Math.round(Math.min(56, Math.max(28, radius * 0.035)));

// Read while styles are clean (before any class changes), so it never forces an extra full-page restyle
function cssDuration(root, property, fallback) {
  const value = getComputedStyle(root).getPropertyValue(property).trim();
  const ms = value.endsWith('ms') ? parseFloat(value) : parseFloat(value) * 1000;
  return Number.isFinite(ms) ? ms : fallback;
}

function setRootTheme(root, theme) {
  root.classList.toggle('dark', theme === 'dark');
  root.style.colorScheme = theme;
}

const isPoint = (origin) => Boolean(origin) && Number.isFinite(origin.x) && Number.isFinite(origin.y);

/**
 * Puts the theme on <html>. With animate, the change is smooth:
 * - With an origin (the toggle's centre, in viewport pixels): a circular reveal. A view transition keeps a snapshot
 *   of the current screen, the theme switches once underneath it, and the new theme is revealed through a circle
 *   that grows from the origin until it reaches the farthest corner of the viewport.
 * - Without an origin: the same view transition with a cross-fade.
 * - Browsers without view transitions: CSS colour transitions on every element for that moment.
 * With prefers-reduced-motion the theme switches immediately. Nothing about the layout is animated.
 */
export function applyTheme(theme, animate = false, origin = null) {
  const root = document.documentElement;
  if (!animate) {
    // A view transition about to apply this same theme must not be pre-empted, or it would have nothing to show
    if (pendingTheme !== theme) setRootTheme(root, theme);
    return;
  }
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  if (reduceMotion) {
    setRootTheme(root, theme);
    return;
  }
  if (typeof document.startViewTransition === 'function') {
    const reveal = isPoint(origin);
    const styles = getComputedStyle(root);
    const duration = reveal ? cssDuration(root, '--theme-reveal-duration', REVEAL_FALLBACK_MS) : 0;
    const easing = reveal ? styles.getPropertyValue('--theme-reveal-easing').trim() || REVEAL_FALLBACK_EASING : '';
    // Soft edge needs the registered --tk-reveal-radius (index.css) and CSS masks; otherwise a crisp clip-path circle
    const soft = reveal && styles.getPropertyValue('--tk-reveal-radius').trim() === '0px' && Boolean(window.CSS?.supports?.('mask-image', 'radial-gradient(#000, transparent)'));
    const reach = reveal ? Math.ceil(Math.hypot(Math.max(origin.x, window.innerWidth - origin.x), Math.max(origin.y, window.innerHeight - origin.y))) : 0;
    const feather = soft ? featherFor(reach) : 0;
    pendingTheme = theme;
    root.classList.add('theme-switching');
    root.classList.toggle('theme-reveal', reveal);
    root.classList.toggle('theme-reveal-soft', soft);
    if (soft) {
      root.style.setProperty('--tk-reveal-x', `${origin.x}px`);
      root.style.setProperty('--tk-reveal-y', `${origin.y}px`);
      root.style.setProperty('--tk-reveal-feather', `${feather}px`);
    }
    // The update callback runs after the browser has captured the old screen, so the switch itself is never seen
    const transition = document.startViewTransition(() => {
      setRootTheme(root, theme);
      if (pendingTheme === theme) pendingTheme = null;
    });
    let revealAnimation = null;
    if (reveal) {
      const { x, y } = origin;
      transition.ready
        .then(() => {
          revealAnimation = soft
            ? // The soft rim must clear the farthest corner too, so the circle ends `feather` beyond it.
              // both: radius 0 until the first frame, full until the transition is torn down (never a frame of the old theme)
              root.animate({ '--tk-reveal-radius': ['0px', `${reach + feather}px`] }, { duration, easing, fill: 'both', pseudoElement: '::view-transition-new(root)' })
            : // backwards: the circle is held at radius 0 until the first frame, so the new theme never shows early
              root.animate(
                { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${reach}px at ${x}px ${y}px)`] },
                { duration, easing, fill: 'backwards', pseudoElement: '::view-transition-new(root)' }
              );
        })
        .catch(() => {}); // superseded by a newer click: that transition takes over
    }
    transition.finished.finally(() => {
      revealAnimation?.cancel();
      if (pendingTheme === null) {
        root.classList.remove('theme-switching', 'theme-reveal', 'theme-reveal-soft');
        ['--tk-reveal-x', '--tk-reveal-y', '--tk-reveal-feather'].forEach((name) => root.style.removeProperty(name));
      }
    });
    return;
  }
  const duration = cssDuration(root, '--theme-transition-duration', TRANSITION_FALLBACK_MS);
  root.classList.add('theme-transition');
  clearTimeout(transitionTimer);
  transitionTimer = setTimeout(() => root.classList.remove('theme-transition'), duration + TRANSITION_SLACK_MS);
  setRootTheme(root, theme);
}


const PREFERENCES = ['LIGHT', 'DARK', 'SYSTEM'];

const ThemeContext = createContext({
  theme: 'light',
  isDark: false,
  preference: 'LIGHT',
  toggleTheme: () => {},
  setTheme: () => {},
  setPreference: () => {},
});

export function ThemeProvider({ children }) {
  const [preference, setPreferenceState] = useState(readStoredPreference);
  const [systemDark, setSystemDark] = useState(systemPrefersDark);
  const theme = resolveTheme(preference, systemDark);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  // System: follow the operating system's light/dark setting while it changes
  useEffect(() => {
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!query) return undefined;
    const onChange = (event) => setSystemDark(event.matches);
    query.addEventListener?.('change', onChange);
    return () => query.removeEventListener?.('change', onChange);
  }, []);

  // Keep other open tabs in step
  useEffect(() => {
    const onStorage = (event) => {
      if (event.key === THEME_STORAGE_KEY) setPreferenceState(readStoredPreference());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  // The most recently requested preference, so rapid repeated changes always start from the latest choice
  const latestPreference = useRef(preference);
  latestPreference.current = preference;

  // The signed-in user's saved preference is the source of truth: when their profile loads (or changes) it replaces whatever the
  // browser remembered, so each user sees their own appearance. It only reacts to the saved value changing, never to local
  // changes, so a change that is still being saved is not undone.
  const { profile } = useCurrentUserProfile();
  const savedPreference = profile?.preferences?.themePreference;
  useEffect(() => {
    if (PREFERENCES.includes(savedPreference) && savedPreference !== latestPreference.current) {
      latestPreference.current = savedPreference;
      storePreference(savedPreference);
      setPreferenceState(savedPreference);
    }
  }, [savedPreference]);

  /**
   * Applies and remembers an appearance preference (LIGHT, DARK or SYSTEM) immediately.
   * origin (optional): viewport point the circular reveal grows from, e.g. the centre of the clicked control.
   * persist (default true): also save it to the user's account. The Settings page passes false because it saves with its own
   * saving / success / error states. Nothing is sent before the user's profile has loaded, so unrelated settings are never overwritten.
   */
  const setPreference = useCallback((next, origin = null, { persist = true } = {}) => {
    const value = PREFERENCES.includes(next) ? next : 'LIGHT';
    latestPreference.current = value;
    storePreference(value);
    applyTheme(resolveTheme(value, systemPrefersDark()), true, origin);
    setPreferenceState(value);
    if (persist && preferencesLoaded()) savePreferences({ themePreference: value }).catch(() => {});
  }, []);

  /** Light or dark, chosen explicitly (used by the header toggle). */
  const setTheme = useCallback((next, origin = null) => setPreference(next === 'dark' ? 'DARK' : 'LIGHT', origin), [setPreference]);

  const toggleTheme = useCallback(
    (origin = null) => {
      const current = resolveTheme(latestPreference.current, systemPrefersDark());
      setPreference(current === 'dark' ? 'LIGHT' : 'DARK', origin);
    },
    [setPreference]
  );

  const value = useMemo(
    () => ({ theme, isDark: theme === 'dark', preference, toggleTheme, setTheme, setPreference }),
    [theme, preference, toggleTheme, setTheme, setPreference]
  );
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  return useContext(ThemeContext);
}
