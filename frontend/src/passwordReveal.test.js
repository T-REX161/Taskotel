import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const srcDir = path.dirname(fileURLToPath(import.meta.url));
const files = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const full = path.join(dir, e.name);
    return e.isDirectory() ? files(full) : /\.jsx$/.test(e.name) ? [full] : [];
  });

describe('password visibility toggles', () => {
  it("hides the browser's built-in reveal button so only our eye button shows", () => {
    const css = fs.readFileSync(path.join(srcDir, 'index.css'), 'utf8');
    assert.ok(/input::-ms-reveal,\s*input::-ms-clear\s*\{\s*display: none;/.test(css));
  });

  it('every password input has exactly one toggle button wired to its own state', () => {
    for (const file of files(srcDir)) {
      const source = fs.readFileSync(file, 'utf8');
      const inputs = [...source.matchAll(/type=\{(\w+) \? 'text' : 'password'\}/g)];
      for (const [, flag] of inputs) {
        const toggles = source.split(`set${flag[0].toUpperCase()}${flag.slice(1)}(!${flag})`).length - 1;
        assert.strictEqual(toggles, 1, `${path.basename(file)}: ${flag} toggle count`);
      }
    }
  });
});
