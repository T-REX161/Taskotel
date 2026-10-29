import React, { useEffect, useState } from 'react';
import { donutArcs, donutCenterTotal, easeOutCubic, legendFade, revealArcs, taskStatusSegments } from '../../utils/dashboardView';

const RADIUS = 54;
const THICKNESS = 15;
const SEGMENT_GAP = 3; // viewBox units, cut from the middle of each segment's slot

const ENTRANCE_MS = 900;
const prefersReducedMotion = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Entrance progress 0 -> 1, played once each time the component mounts (so again whenever the Dashboard is revisited) and never
 * restarted by re-renders. With reduced motion it starts at 1: the final chart, no counting, no stagger. The frame request is
 * cancelled on unmount (and by Strict Mode's extra effect run), so nothing keeps running afterwards.
 */
function useEntrance() {
  const [progress, setProgress] = useState(() => (prefersReducedMotion() ? 1 : 0));
  useEffect(() => {
    if (prefersReducedMotion()) {
      setProgress(1);
      return undefined;
    }
    let frame;
    let start;
    setProgress(0);
    const tick = (now) => {
      start = start ?? now;
      const raw = Math.min(1, (now - start) / ENTRANCE_MS);
      setProgress(raw);
      if (raw < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, []);
  return progress;
}

/**
 * TaskStatusSummary — "Task Status Overview": a donut with the total in the middle and the labelled counts beside it.
 * The counts come straight from the dashboard summary; staff see their own tasks (and no cancelled count). Statuses
 * with no tasks stay in the legend, muted, and simply have no arc. Pointing at (or focusing) a legend row softly
 * emphasises its segment.
 *
 * Props:
 *   data    – summary payload containing status counts
 *   isStaff – boolean indicating whether current user has STAFF role view
 */
/*
 * Layout: the card is as tall as its content (the grid does not stretch it to the workload panel); the donut and legend sit
 * side by side and are centred together under the heading. They only stack when the card cannot
 * hold both (flex-wrap): the donut is 160-224px depending on the width, the legend needs about 170px and the gap is 20px,
 * so side by side takes roughly 372-420px against the ~430px the card has at 1440px. The donut is therefore sized in steps.
 */
export default function TaskStatusSummary({ data, isStaff }) {
  const { total, rows } = taskStatusSegments(data, isStaff);
  const arcs = donutArcs(rows, RADIUS, SEGMENT_GAP);
  const centre = donutCenterTotal(data, total, isStaff);
  const [active, setActive] = useState(null);
  const progress = useEntrance();
  const eased = easeOutCubic(progress);
  const shownArcs = revealArcs(arcs, eased, RADIUS);
  const animating = progress < 1;

  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-[0_1px_2px_rgba(16,24,40,0.04)] p-5 sm:p-6 flex flex-col">
      <h2 className="text-lg font-semibold text-gray-900">Task Status Overview</h2>
      <p className="text-xs text-gray-500 mt-0.5">
        {isStaff ? 'Your tasks by status' : 'All tasks by status'}
        {total > 0 ? ` · ${total} in total` : ''}
      </p>

      <div className="mt-5 flex flex-wrap items-center justify-center gap-x-5 gap-y-5">
        <figure className="relative aspect-square w-48 min-[1200px]:w-40 min-[1320px]:w-44 min-[1400px]:w-52 min-[1440px]:w-56 shrink-0 m-0" role="img" aria-label={`${total} tasks: ${rows.map((r) => `${r.count} ${r.label}`).join(', ')}`}>
          <svg viewBox="0 0 140 140" className="w-full h-full -rotate-90" aria-hidden="true">
            {/* The track only shows when there is nothing to draw; otherwise the gaps between segments show the card */}
            {arcs.length === 0 && <circle cx="70" cy="70" r={RADIUS} fill="none" strokeWidth={THICKNESS} stroke="currentColor" className="text-gray-200" />}
            {shownArcs.map((arc) => (
              <circle
                key={arc.key}
                cx="70"
                cy="70"
                r={RADIUS}
                fill="none"
                strokeWidth={THICKNESS}
                className={`${arc.stroke} transition-opacity duration-200 ${active && active !== arc.key ? 'opacity-30' : 'opacity-100'}`}
                strokeDasharray={`${arc.length} ${arc.gap}`}
                strokeDashoffset={arc.offset}
              />
            ))}
          </svg>
          {centre !== null && (
            <figcaption className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
              <span className="text-4xl font-bold text-gray-900 leading-none tabular-nums">{animating ? Math.round(centre * eased) : centre}</span>
              <span className="mt-2 text-[11px] font-medium uppercase tracking-wider text-gray-500">{total === 0 ? 'No tasks' : centre === 1 ? 'Task' : 'Tasks'}</span>
            </figcaption>
          )}
        </figure>

        <ul className="min-w-[10.5rem] max-w-full divide-y divide-gray-100">
          {rows.map((row, index) => (
            <li
              key={row.key}
              style={animating ? { opacity: legendFade(progress, index) * (row.count === 0 ? 0.55 : 1) } : undefined}
              tabIndex={row.count > 0 ? 0 : undefined}
              onMouseEnter={() => row.count > 0 && setActive(row.key)}
              onMouseLeave={() => setActive(null)}
              onFocus={() => setActive(row.key)}
              onBlur={() => setActive(null)}
              className={`flex items-center justify-between gap-4 py-2.5 text-sm rounded-sm transition-opacity focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${row.count === 0 ? 'opacity-55' : ''}`}
            >
              <span className="flex items-center gap-2.5 whitespace-nowrap">
                <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${row.dot}`} aria-hidden="true" />
                <span className="font-medium text-gray-700 truncate">{row.label}</span>
              </span>
              <span className="shrink-0 tabular-nums text-right">
                <span className="font-semibold text-gray-900">{row.count}</span>
                <span className="ml-2 inline-block w-9 text-xs text-gray-400">{row.percent}%</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
