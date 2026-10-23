import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

// The chosen theme is remembered per browser. index.html reads the same key before the first paint,
// so a page load or refresh never flashes the wrong theme.
export const THEME_STORAGE_KEY = 'taskotel-theme';
const TRANSITION_MS = 250;

export function readStoredTheme() {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function storeTheme(theme) {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // Storage blocked (private mode): the theme still applies for this visit
  }
}

let transitionTimer;

/** Puts the theme on <html>. With animate, colours cross-fade briefly (only during the switch, not on every hover). */
export function applyTheme(theme, animate = false) {
  const root = document.documentElement;
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  if (animate && !reduceMotion) {
    root.classList.add('theme-transition');
    clearTimeout(transitionTimer);
    transitionTimer = setTimeout(() => root.classList.remove('theme-transition'), TRANSITION_MS);
  }
  root.classList.toggle('dark', theme === 'dark');
  root.style.colorScheme = theme;
}

const ThemeContext = createContext({ theme: 'light', isDark: false, toggleTheme: () => {}, setTheme: () => {} });

export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(readStoredTheme);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  // Keep other open tabs in step
  useEffect(() => {
    const onStorage = (event) => {
      if (event.key === THEME_STORAGE_KEY) setThemeState(readStoredTheme());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const setTheme = useCallback((next) => {
    const value = next === 'dark' ? 'dark' : 'light';
    storeTheme(value);
    applyTheme(value, true);
    setThemeState(value);
  }, []);

  const toggleTheme = useCallback(() => setTheme(theme === 'dark' ? 'light' : 'dark'), [theme, setTheme]);

  const value = useMemo(() => ({ theme, isDark: theme === 'dark', toggleTheme, setTheme }), [theme, toggleTheme, setTheme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  return useContext(ThemeContext);
}
