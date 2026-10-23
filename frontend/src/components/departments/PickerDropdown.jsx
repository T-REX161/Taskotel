import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';

// Room the list needs (max height plus margin); if the scrolling form area has less below the button, open upward
const PANEL_SPACE = 224;

// A compact dropdown whose button and options show only graphics (no text). Options are real buttons
// (role="option", named for screen readers); Escape closes the list without closing the dialog.
export default function PickerDropdown({ id, labelId, disabled, trigger, panelClass, children }) {
  const [open, setOpen] = useState(false);
  const [dropUp, setDropUp] = useState(false);
  const wrapperRef = useRef(null);
  const triggerRef = useRef(null);

  // Open downward unless the surrounding scroll area would cut the list off and there is more room above
  useLayoutEffect(() => {
    if (!open || !triggerRef.current) return;
    const button = triggerRef.current.getBoundingClientRect();
    const area = triggerRef.current.closest('.overflow-y-auto')?.getBoundingClientRect();
    const bottomLimit = area ? area.bottom : window.innerHeight;
    const topLimit = area ? area.top : 0;
    const below = bottomLimit - button.bottom;
    const above = button.top - topLimit;
    setDropUp(below < PANEL_SPACE && above > below);
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  const onKeyDown = (event) => {
    if (event.key === 'Escape' && open) {
      event.stopPropagation();
      setOpen(false);
      triggerRef.current?.focus();
    }
  };

  const select = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  return (
    <div className="relative" ref={wrapperRef} onKeyDown={onKeyDown}>
      <button
        id={id}
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-labelledby={labelId}
        disabled={disabled}
        onClick={() => setOpen((prev) => !prev)}
        className="h-10 pl-1.5 pr-2 border border-gray-200 rounded-lg bg-white inline-flex items-center gap-1.5 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-emerald-500 transition-colors cursor-pointer disabled:bg-gray-50 disabled:cursor-not-allowed"
      >
        {trigger}
        <svg className="w-4 h-4 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {open && (
        <div role="listbox" aria-labelledby={labelId} className={`absolute left-0 z-30 p-2 bg-white border border-gray-200 rounded-xl shadow-lg ${dropUp ? 'bottom-full mb-1.5' : 'top-full mt-1.5'} ${panelClass}`}>
          {children(select)}
        </div>
      )}
    </div>
  );
}
