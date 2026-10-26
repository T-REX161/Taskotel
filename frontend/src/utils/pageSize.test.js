import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE, normalizePageSize } from './pageSize.js';
import { getPaginationState } from './assignmentUtils.js';

const list = Array.from({ length: 23 }, (_, i) => `row${i + 1}`);
const pages = (items, pageSize) => {
  const { totalPages } = getPaginationState({ totalItems: items.length, currentPage: 1, pageSize });
  return Array.from({ length: totalPages }, (_, i) => {
    const state = getPaginationState({ totalItems: items.length, currentPage: i + 1, pageSize });
    return { state, rows: items.slice(state.startIndex, state.endIndex) };
  });
};

describe('rows-per-page options', () => {
  it('offers 5, 10 and 15, defaulting to 10', () => {
    assert.deepStrictEqual(PAGE_SIZE_OPTIONS, [5, 10, 15]);
    assert.strictEqual(DEFAULT_PAGE_SIZE, 10);
  });

  it('accepts only the offered sizes', () => {
    assert.strictEqual(normalizePageSize(15), 15);
    assert.strictEqual(normalizePageSize('5'), 5);
    assert.strictEqual(normalizePageSize(7), 10);
    assert.strictEqual(normalizePageSize(undefined, 5), 5);
  });
});

describe('pagination with each page size (23 rows)', () => {
  for (const [size, expected] of [[5, [5, 5, 5, 5, 3]], [10, [10, 10, 3]], [15, [15, 8]]]) {
    it(`${size} per page: ${expected.length} pages, the last holding only the remainder`, () => {
      const result = pages(list, size);
      assert.deepStrictEqual(result.map((p) => p.rows.length), expected);
      assert.deepStrictEqual(result.flatMap((p) => p.rows), list, 'Every row once, in order');
      const last = result.at(-1).state;
      assert.deepStrictEqual([last.rangeStart, last.rangeEnd, last.hasNext, result[0].state.hasPrevious], [23 - expected.at(-1) + 1, 23, false, false]);
    });
  }

  it('a page that no longer exists after a size change falls back to the last page', () => {
    // On page 5 of 5 (size 5), switching to 15 leaves only 2 pages
    assert.strictEqual(getPaginationState({ totalItems: 23, currentPage: 5, pageSize: 15 }).page, 2);
  });
});
