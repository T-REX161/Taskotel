import React, { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { SlidersIcon } from '../icons/Icons';
import { PAGE_SIZE_OPTIONS } from '../../utils/pageSize';
import { getNextMenuItemIndex } from '../../utils/assignmentUtils';

const MENU_WIDTH = 160;
const MENU_HEIGHT = 140; // caption + three options
const GAP = 6;
const MARGIN = 8;
const FOCUS_CLASS = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1';

/**
 * Rows-per-page toggle shown beside a list's pagination controls: "10 rows" with a sliders icon. The options are
 * hidden until the button is clicked; clicking again, choosing an option, clicking outside or pressing Escape
 * hides them. The options are rendered through a portal so a table card's overflow-hidden never clips them.
 * Keyboard: opening moves focus to the selected size, the arrow keys move between sizes, Enter chooses one,
 * Escape returns to the button and Tab leaves the list from the button.
 *
 * Props:
 *  - value: the current page size
 *  - onChange(size): called only when a different size is chosen
 *  - options: sizes to offer (default 5, 10, 15)
 *  - placement: 'top' (above the button, for footers) or 'bottom' (below it); flips when there is no room
 *  - label: accessible name and tooltip
 */
export default function PageSizeToggle({ value, onChange, options = PAGE_SIZE_OPTIONS, placement = 'top', label = 'Rows per page' }) {
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState(null);
  const buttonRef = useRef(null);
  const menuRef = useRef(null);
  const menuId = useId();

  const place = useCallback(() => {
    const button = buttonRef.current;
    if (!button) return;
    const rect = button.getBoundingClientRect();
    const spaceAbove = rect.top;
    const spaceBelow = window.innerHeight - rect.bottom;
    const fitsAbove = spaceAbove >= MENU_HEIGHT + GAP;
    const fitsBelow = spaceBelow >= MENU_HEIGHT + GAP;
    const above = placement === 'top' ? fitsAbove || !fitsBelow : !fitsBelow && fitsAbove;
    const left = Math.max(MARGIN, Math.min(rect.left, window.innerWidth - MENU_WIDTH - MARGIN));
    setPosition(above ? { left, bottom: window.innerHeight - rect.top + GAP } : { left, top: rect.bottom + GAP });
  }, [placement]);

  useLayoutEffect(() => {
    if (open) {
      // A bigger page pushes the footer down, so the focused button may be out of view when reopened from the keyboard
      buttonRef.current?.scrollIntoView({ block: 'nearest' });
      place();
    } else setPosition(null);
  }, [open, place]);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event) => {
      if (!buttonRef.current?.contains(event.target) && !menuRef.current?.contains(event.target)) setOpen(false);
    };
    const onKeyDown = (event) => {
      if (event.key === 'Escape' || event.key === 'Tab') {
        // Escape returns to the button; Tab continues from it to the next control
        setOpen(false);
        buttonRef.current?.focus();
        return;
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const items = menuRef.current ? Array.from(menuRef.current.querySelectorAll('[role="option"]')) : [];
        const nextIndex = getNextMenuItemIndex(items.indexOf(document.activeElement), items.length, event.key);
        if (nextIndex >= 0) {
          event.preventDefault();
          items[nextIndex].focus();
        }
      }
    };
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, place]);

  // Once the options are placed, move focus to the selected size (keyboard users land inside the list)
  const placed = open && position !== null;
  useEffect(() => {
    if (!placed || !menuRef.current || menuRef.current.contains(document.activeElement)) return;
    (menuRef.current.querySelector('[aria-selected="true"]') || menuRef.current.querySelector('[role="option"]'))?.focus();
  }, [placed]);

  const choose = (size) => {
    setOpen(false);
    buttonRef.current?.focus();
    if (size !== value) onChange(size);
  };

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((current) => !current)}
        className={`inline-flex items-center gap-1.5 h-8 pl-2.5 pr-2 rounded-lg border text-xs font-medium whitespace-nowrap transition-colors cursor-pointer ${FOCUS_CLASS} ${
          open
            ? 'border-emerald-500 bg-emerald-50 text-emerald-700'
            : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50 hover:border-gray-300'
        }`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`${label}: ${value}`}
        title={label}
      >
        <SlidersIcon className="w-3.5 h-3.5 shrink-0" />
        <span>{value} rows</span>
        <svg className={`w-3 h-3 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>

      {open && position && createPortal(
        <div
          ref={menuRef}
          id={menuId}
          role="listbox"
          aria-label={label}
          style={{ position: 'fixed', left: `${position.left}px`, width: `${MENU_WIDTH}px`, zIndex: 9999, ...(position.top !== undefined ? { top: `${position.top}px` } : { bottom: `${position.bottom}px` }) }}
          className="rounded-xl border border-gray-200 bg-white shadow-lg p-1 select-none"
        >
          <p className="px-2.5 pt-1.5 pb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{label}</p>
          {options.map((size) => {
            const selected = size === value;
            return (
              <button
                key={size}
                type="button"
                role="option"
                aria-selected={selected}
                onClick={() => choose(size)}
                className={`w-full flex items-center justify-between gap-3 px-2.5 py-1.5 rounded-lg text-xs transition-colors cursor-pointer ${FOCUS_CLASS} ${
                  selected ? 'bg-emerald-50 text-emerald-700 font-semibold' : 'text-gray-700 hover:bg-gray-50'
                }`}
              >
                <span>{size} rows</span>
                {selected && (
                  <svg className="w-3.5 h-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                )}
              </button>
            );
          })}
        </div>,
        document.body
      )}
    </>
  );
}
