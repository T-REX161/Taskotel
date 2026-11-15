/*
 * Motion tokens for Taskotel: one small, restrained set of durations, easings, and entrance and exit animations.
 *
 * Like themePalette.cjs, this is the single source: tailwind.config.cjs turns it into utilities
 * (duration-fast, ease-enter, animate-dialog-in, ...) and CSS variables (--tk-duration-base, --tk-ease-enter, ...),
 * and the tests read it directly. Reduced-motion handling for these utilities lives in src/index.css.
 *
 * CommonJS on purpose (see tailwind.config.cjs): Tailwind loads it with require(). No dependencies.
 */

// Durations. Nothing in the app should need others: fast for exits and presses, base for small entrances,
// moderate for dialogs, slow for the largest movements (bars, cards).
const DURATIONS = {
  fast: '120ms',
  base: '160ms',
  moderate: '200ms',
  slow: '250ms',
};

// Easings. Enter decelerates into place, exit accelerates away, standard for state changes (hover, colour, size).
const EASINGS = {
  enter: 'cubic-bezier(0, 0, 0.2, 1)',
  exit: 'cubic-bezier(0.4, 0, 1, 1)',
  standard: 'cubic-bezier(0.2, 0, 0, 1)',
};

// Keyframes. Small distances only: nothing slides far, bounces or overshoots. Names are prefixed with "tk-" so they
// never clash with Tailwind's own keyframes or the existing ones in index.css (insightIn, clockRollIn, ...).
const KEYFRAMES = {
  'tk-fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
  'tk-fade-out': { from: { opacity: '1' }, to: { opacity: '0' } },
  'tk-dialog-in': { from: { opacity: '0', transform: 'scale(0.98)' }, to: { opacity: '1', transform: 'scale(1)' } },
  'tk-menu-in': { from: { opacity: '0', transform: 'translateY(-2px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
  'tk-toast-in': { from: { opacity: '0', transform: 'translateY(-4px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
  // Exits: the same small movement played backwards, so a closing element leaves the way it came in.
  'tk-dialog-out': { from: { opacity: '1', transform: 'scale(1)' }, to: { opacity: '0', transform: 'scale(0.98)' } },
  'tk-menu-out': { from: { opacity: '1', transform: 'translateY(0)' }, to: { opacity: '0', transform: 'translateY(-2px)' } },
  'tk-toast-out': { from: { opacity: '1', transform: 'translateY(0)' }, to: { opacity: '0', transform: 'translateY(-4px)' } },
};

// Animation utilities (Tailwind `animate-<name>`). Each plays once; "both" keeps the first frame until it starts and the
// last frame after it ends, so nothing flashes (a closing element stays hidden until it is unmounted).
// Exits are shorter than entrances (fast, accelerating away) so closing never holds up the next interaction.
const ANIMATIONS = {
  'fade-in': `tk-fade-in ${DURATIONS.base} ${EASINGS.enter} both`,
  'fade-out': `tk-fade-out ${DURATIONS.fast} ${EASINGS.exit} both`,
  'dialog-in': `tk-dialog-in ${DURATIONS.moderate} ${EASINGS.enter} both`,
  'dialog-out': `tk-dialog-out ${DURATIONS.fast} ${EASINGS.exit} both`,
  'menu-in': `tk-menu-in ${DURATIONS.base} ${EASINGS.enter} both`,
  'menu-out': `tk-menu-out ${DURATIONS.fast} ${EASINGS.exit} both`,
  'toast-in': `tk-toast-in ${DURATIONS.base} ${EASINGS.enter} both`,
  'toast-out': `tk-toast-out ${DURATIONS.fast} ${EASINGS.exit} both`,
};

/** How long a closing element stays mounted for its exit animation (DURATIONS.fast), in milliseconds. */
const EXIT_MS = parseInt(DURATIONS.fast, 10);

/** The same tokens as CSS custom properties, for hand-written CSS (for example index.css). */
function motionVariables() {
  const vars = {};
  for (const [name, value] of Object.entries(DURATIONS)) vars[`--tk-duration-${name}`] = value;
  for (const [name, value] of Object.entries(EASINGS)) vars[`--tk-ease-${name}`] = value;
  return vars;
}

module.exports = { DURATIONS, EASINGS, KEYFRAMES, ANIMATIONS, EXIT_MS, motionVariables };
