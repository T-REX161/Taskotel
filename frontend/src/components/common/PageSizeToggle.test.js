import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Line endings are normalised so the checks behave the same on LF and CRLF (Windows) checkouts
const source = fs.readFileSync(path.join(__dirname, 'PageSizeToggle.jsx'), 'utf8').replace(/\r\n/g, '\n');

describe('PageSizeToggle', () => {
  it('shows the current size on the button, and keeps the options hidden until it is clicked', () => {
    assert.ok(source.includes('const [open, setOpen] = useState(false);'), 'Hidden by default');
    assert.ok(source.includes('<span>{value} rows</span>') && source.includes('<SlidersIcon'));
    assert.ok(source.includes('onClick={() => setOpen((current) => !current)}'), 'Clicking again hides the options');
    assert.ok(source.includes('{open && position && createPortal('), 'The options exist only while open');
  });

  it('offers 5, 10 and 15 by default and marks the selected one', () => {
    assert.ok(source.includes('options = PAGE_SIZE_OPTIONS'));
    assert.ok(source.includes('role="option"') && source.includes('aria-selected={selected}'));
    assert.ok(source.includes("'bg-emerald-50 text-emerald-700 font-semibold'"), 'Taskotel green for the selected size');
  });

  it('closes after a choice, on an outside click and on Escape; only a different size is reported', () => {
    assert.ok(/const choose = \(size\) => \{\s*setOpen\(false\);[\s\S]*?if \(size !== value\) onChange\(size\);/.test(source));
    assert.ok(source.includes("document.addEventListener('pointerdown', onPointerDown)") && source.includes('!menuRef.current?.contains(event.target)'));
    assert.ok(/if \(event\.key === 'Escape' \|\| event\.key === 'Tab'\) \{[^}]*setOpen\(false\);\s*buttonRef\.current\?\.focus\(\);/.test(source), 'Escape and Tab close and return focus to the button');
  });

  it('can be used from the keyboard: focus starts on the selected size and the arrow keys move between sizes', () => {
    assert.ok(source.includes("menuRef.current.querySelector('[aria-selected=\"true\"]')") && /\}, \[placed\]\);/.test(source), 'Focus moves to the selected size once the list is placed');
    assert.ok(source.includes("if (event.key === 'ArrowDown' || event.key === 'ArrowUp')") && source.includes('getNextMenuItemIndex(items.indexOf(document.activeElement), items.length, event.key)'), 'Same arrow-key wrapping as the row action menus');
  });

  it('is accessible and never clipped by a table card', () => {
    assert.ok(source.includes('aria-haspopup="listbox"') && source.includes('aria-expanded={open}') && source.includes('aria-label={`${label}: ${value}`}') && source.includes('title={label}'));
    assert.ok(source.includes("position: 'fixed'") && source.includes('document.body'), 'Rendered in a portal');
    assert.ok(source.includes("const above = placement === 'top' ? fitsAbove || !fitsBelow : !fitsBelow && fitsAbove;"), 'Opens above footers, below headers, and flips when there is no room');
    assert.ok(source.includes('Math.max(MARGIN, Math.min(rect.left, window.innerWidth - MENU_WIDTH - MARGIN))'), 'Stays inside narrow screens');
    assert.ok(/if \(open\) \{\s*\/\/[^\n]*\n\s*buttonRef\.current\?\.scrollIntoView\(\{ block: 'nearest' \}\);\s*place\(\);/.test(source), 'Opening brings an off-screen button into view first');
  });
});
