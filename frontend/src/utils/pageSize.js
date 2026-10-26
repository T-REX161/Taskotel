/*
 * Rows-per-page choices shared by every paginated list (the toggle beside each list's pagination controls).
 * Plain JavaScript so the pages and the tests use the same values.
 */
export const PAGE_SIZE_OPTIONS = [5, 10, 15];

/** The default for lists that do not set their own (the Workload list keeps its original 5). */
export const DEFAULT_PAGE_SIZE = 10;

/** A supported page size, or the fallback when the value is not one of the options. */
export function normalizePageSize(value, fallback = DEFAULT_PAGE_SIZE) {
  const size = Number(value);
  return PAGE_SIZE_OPTIONS.includes(size) ? size : fallback;
}
