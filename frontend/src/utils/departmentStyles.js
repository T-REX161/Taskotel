/*
 * Icon and colour for a department, from stable keys stored with the department in the database.
 *
 * The database holds only keys (for example iconKey "sprout", colorKey "green"); this file is the single place
 * that turns a key into an icon component name and Tailwind classes. Colours follow the department's identity
 * (its stored colorKey, or for the six built-ins the same colour they always had), never its position in a list.
 * A missing or unknown key falls back to the building icon and neutral grey.
 */
import { getRegisteredDepartment } from './departmentRegistry.js';

// Must match DepartmentCatalog.ICON_KEYS in the backend (a test compares them)
export const ICON_KEYS = [
  'building', 'users', 'clipboard', 'calendar', 'clock',
  'briefcase', 'package', 'truck', 'monitor', 'sprout',
  'sparkles', 'bed', 'bell', 'coffee', 'utensils',
  'wrench', 'shield',
];

// Every class is written out in full so Tailwind generates it.
export const COLOR_STYLES = {
  gray: { classes: 'bg-gray-100 text-gray-700', swatch: 'bg-gray-400', label: 'Gray' },
  green: { classes: 'bg-green-100 text-green-700', swatch: 'bg-green-500', label: 'Mint green' },
  blue: { classes: 'bg-blue-100 text-blue-700', swatch: 'bg-blue-500', label: 'Blue' },
  purple: { classes: 'bg-purple-100 text-purple-700', swatch: 'bg-purple-500', label: 'Purple' },
  amber: { classes: 'bg-amber-100 text-amber-700', swatch: 'bg-amber-500', label: 'Amber' },
  orange: { classes: 'bg-orange-100 text-orange-700', swatch: 'bg-orange-500', label: 'Orange' },
  cyan: { classes: 'bg-cyan-100 text-cyan-700', swatch: 'bg-cyan-500', label: 'Cyan' },
  rose: { classes: 'bg-rose-100 text-rose-700', swatch: 'bg-rose-500', label: 'Rose' },
  indigo: { classes: 'bg-indigo-100 text-indigo-700', swatch: 'bg-indigo-500', label: 'Indigo' },
  teal: { classes: 'bg-teal-100 text-teal-700', swatch: 'bg-teal-500', label: 'Teal' },
};
export const COLOR_KEYS = Object.keys(COLOR_STYLES);

export const DEFAULT_ICON_KEY = 'building';
export const DEFAULT_COLOR_KEY = 'gray';

// The built-in six, used until (or unless) the API supplies keys, for example against an older backend
const BUILT_IN = {
  HOUSEKEEPING: { iconKey: 'sprout', colorKey: 'green' },
  FRONT_DESK: { iconKey: 'bell', colorKey: 'blue' },
  MAINTENANCE: { iconKey: 'wrench', colorKey: 'purple' },
  ROOM_SERVICE: { iconKey: 'coffee', colorKey: 'amber' },
  FOOD_AND_BEVERAGE: { iconKey: 'utensils', colorKey: 'orange' },
  SECURITY: { iconKey: 'shield', colorKey: 'cyan' },
};

// Alternative spellings that refer to the same department
const ALIASES = {
  F_AND_B: 'FOOD_AND_BEVERAGE',
  FOOD_BEVERAGE: 'FOOD_AND_BEVERAGE',
  FOOD_AND_BEVERAGES: 'FOOD_AND_BEVERAGE',
  FRONTDESK: 'FRONT_DESK',
  HOUSE_KEEPING: 'HOUSEKEEPING',
};

export function normalizeDepartmentKey(department) {
  const key = String(department || '')
    .trim()
    .toUpperCase()
    .replace(/&/g, '_AND_')
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return ALIASES[key] || key;
}

const validIcon = (key) => (ICON_KEYS.includes(key) ? key : undefined);
const validColor = (key) => (Object.prototype.hasOwnProperty.call(COLOR_STYLES, key) ? key : undefined);

/**
 * Style for a department, given its code or name, or a department object ({ code, iconKey, colorKey }).
 * Returns { key, iconKey, colorKey, classes, swatch }.
 */
export function getDepartmentStyle(department) {
  const object = department && typeof department === 'object' ? department : null;
  const code = object ? object.code : department;
  const key = normalizeDepartmentKey(code);
  const known = getRegisteredDepartment(key) || getRegisteredDepartment(code);
  const builtIn = BUILT_IN[key];

  const iconKey = validIcon(object?.iconKey) || validIcon(known?.iconKey) || builtIn?.iconKey || DEFAULT_ICON_KEY;
  const colorKey = validColor(object?.colorKey) || validColor(known?.colorKey) || builtIn?.colorKey || DEFAULT_COLOR_KEY;
  return { key, iconKey, colorKey, classes: COLOR_STYLES[colorKey].classes, swatch: COLOR_STYLES[colorKey].swatch };
}

export const DEPARTMENT_STYLE_KEYS = Object.keys(BUILT_IN);
