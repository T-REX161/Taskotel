// CommonJS on purpose. Tailwind 3 loads its config with require(); on Node 22.12+ a config written as an ES module
// (this package is "type": "module") goes through Node's ES-module cache, which Tailwind cannot clear, so a running
// dev server silently kept the old config after an edit. As CommonJS, edits here and in themePalette.cjs reload.
const plugin = require('tailwindcss/plugin');
const { PROPERTIES, paletteFor, surfaceColors, themeVariables } = require('./src/theme/themePalette.cjs');
const motion = require('./src/theme/motion.cjs');

// Dark mode: the `dark` class on <html> (set by ThemeProvider, and before first paint by index.html).
// Colours that look different in dark mode read CSS variables; see src/theme/themePalette.cjs.
const themed = Object.fromEntries(
  Object.entries(PROPERTIES).map(([prop, key]) => [key, ({ theme }) => paletteFor(prop, theme('colors'))])
);

/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    ...themed,
    extend: {
      colors: {
        surface: surfaceColors(),
      },
      // Motion tokens (src/theme/motion.cjs): duration-fast|base|moderate|slow, ease-enter|exit|standard and
      // animate-fade-in|fade-out|dialog-in|menu-in|toast-in. Reduced motion for these is handled in src/index.css.
      transitionDuration: motion.DURATIONS,
      transitionTimingFunction: motion.EASINGS,
      keyframes: motion.KEYFRAMES,
      animation: motion.ANIMATIONS,
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'Inter', 'system-ui', '-apple-system', 'sans-serif'],
      },
    },
  },
  plugins: [
    plugin(({ addBase, theme }) => {
      const { light, dark } = themeVariables(theme('colors'));
      addBase({ ':root': { ...light, ...motion.motionVariables() }, 'html.dark': dark });
    }),
  ],
}
