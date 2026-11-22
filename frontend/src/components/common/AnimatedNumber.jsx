import React, { useEffect, useRef, useState } from 'react';

const reducedMotion = () => typeof window !== 'undefined' && Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);

// Slows down towards the end, like the bars' own easing (see .tk-bar-grow in index.css)
const easeOut = (p) => 1 - (1 - p) ** 4;

/**
 * A number that counts up to its value when it first appears (from 0, after an optional delay), and glides to a new value when
 * the value changes later. With reduced motion it simply shows the value. The animated digits are hidden from assistive
 * technology, which reads the final value only, so a screen reader never hears the count.
 *
 * Props: value (number), duration (ms, default 700: the same as the bars), delay (ms, only before the first count), decimals (default 0:
 * whole numbers; 1 shows one decimal, without a trailing .0).
 */
export default function AnimatedNumber({ value, duration = 700, delay = 0, decimals = 0 }) {
  const target = Number(value);
  const animate = Number.isFinite(target) && !reducedMotion();
  const [shown, setShown] = useState(animate ? 0 : target);
  const shownRef = useRef(shown);
  const firstRun = useRef(true);

  useEffect(() => {
    if (!Number.isFinite(target) || reducedMotion()) {
      shownRef.current = target;
      setShown(target);
      return undefined;
    }
    const from = shownRef.current;
    const wait = firstRun.current ? delay : 0;
    firstRun.current = false;
    let frame = 0;
    const timer = setTimeout(() => {
      const startedAt = performance.now();
      const tick = (now) => {
        const progress = Math.min(1, (now - startedAt) / duration);
        const current = from + (target - from) * easeOut(progress);
        shownRef.current = current;
        setShown(current);
        if (progress < 1) frame = requestAnimationFrame(tick);
      };
      frame = requestAnimationFrame(tick);
    }, wait);
    return () => {
      clearTimeout(timer);
      cancelAnimationFrame(frame);
    };
  }, [target, duration, delay]);

  return (
    <>
      <span aria-hidden="true">{Number.isFinite(target) ? String(Math.round(shown * 10 ** decimals) / 10 ** decimals) : value}</span>
      <span className="sr-only">{value}</span>
    </>
  );
}
