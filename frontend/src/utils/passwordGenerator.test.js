import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateTemporaryPassword, passwordBase, randomInt, copyText } from './passwordGenerator.js';
import { evaluatePasswordRules } from './passwordPolicy.js';

const names = ['Ayush Mallick', 'Al', 'A', 'Li Wu', "Mary-Jane O'Neil", '  ', '', 'José Álvarez', '12345', 'x'.repeat(80), '李雷', 'a.b@hotel.com'];

describe('generateTemporaryPassword', () => {
  const SPECIAL = /[@#$%&!*]/;
  const sample = (name, n = 2000) => Array.from({ length: n }, () => generateTemporaryPassword(name));

  it('uses only the first word of the name: "Dua Dino" gives Dua and never Dino', () => {
    for (const pwd of sample('Dua Dino', 500)) {
      assert.ok(pwd.includes('Dua'), pwd);
      assert.ok(!/dino/i.test(pwd) && !/D.*i.*n.*o/.test(pwd.replace(/Dua/, '').replace(/\d/g, '')), pwd);
      assert.match(pwd, /^(?:[@#$%&!*]Dua\d{4}|Dua\d*[@#$%&!*]\d*)$/, pwd);
    }
  });

  it('is exactly 8 characters: name, one special character, and digits in every other position', () => {
    for (const name of names) {
      const base = passwordBase(name);
      for (const pwd of sample(name, 300)) {
        assert.equal(pwd.length, 8, `${name} -> ${pwd}`);
        assert.equal(pwd.replace(/[^@#$%&!*]/g, '').length, 1, `one special in ${pwd}`);
        const withoutName = pwd.replace(base, '');
        assert.equal(withoutName.length, 8 - base.length);
        assert.match(withoutName.replace(SPECIAL, ''), /^\d+$/, `only digits besides the name and special: ${pwd}`);
        assert.equal(pwd.replace(/\D/g, '').length, 7 - base.length, `digit count in ${pwd}`);
      }
    }
  });

  it('satisfies all five password rules for short, long, empty, punctuation-only and multi-word names', () => {
    for (const name of names) {
      for (const pwd of sample(name, 200)) {
        const r = evaluatePasswordRules(pwd);
        assert.ok(r.ruleMinLength && r.ruleUppercase && r.ruleLowercase && r.ruleNumber && r.ruleSpecialChar && r.hasNoWhitespace, `${name} -> ${pwd}`);
      }
    }
  });

  it('can put the special character at the beginning, in the middle and at the end, never inside the name', () => {
    const at = new Set(sample('Dua Dino', 3000).map((p) => p.search(SPECIAL)));
    assert.deepEqual([...at].sort((x, y) => x - y), [0, 3, 4, 5, 6, 7], 'start, directly after the name, middle of the digits, end');
    for (const pwd of sample('Rahul Roy', 1000)) assert.ok(pwd.includes('Rahul'), `the name stays whole in ${pwd}`);
    assert.deepEqual([...new Set(sample('Rahul Roy', 2000).map((p) => p.search(SPECIAL)))].sort((x, y) => x - y), [0, 5, 6, 7]);
  });

  it('handles first names that are too long, too short or unusable without producing an invalid password', () => {
    assert.equal(passwordBase('Maximilian Roy'), 'Maximi', 'a long first name is cut to 6 letters so everything fits in 8');
    assert.equal(passwordBase('Anna-Maria Roy'), 'AnnaMa');
    for (const pwd of sample('Maximilian Roy', 200)) assert.match(pwd, /^(?:[@#$%&!*]Maximi\d|Maximi[@#$%&!*]\d|Maximi\d[@#$%&!*])$/, pwd);
    for (const name of ['', '   ', '!!!', '12345', '李雷', undefined, null, 'A', 'A Smith']) assert.equal(passwordBase(name), 'Staff', String(name));
  });

  it('keeps accents and punctuation out, capitalises the initial and keeps the rest as typed', () => {
    assert.equal(passwordBase('ayush mallick'), 'Ayush');
    assert.equal(passwordBase('RAHUL SHARMA'), 'Rahul');
    assert.equal(passwordBase('McDonald Ray'), 'McDona');
    assert.equal(passwordBase('José Álvarez'), 'Jose');
    assert.equal(passwordBase("  O'Neil  Li"), 'ONeil');
    assert.equal(passwordBase('Al Li'), 'Al');
  });

  it('refresh uses the latest name and gives a new random password', () => {
    const first = generateTemporaryPassword('Dua Dino');
    const again = generateTemporaryPassword('Dua Dino');
    const renamed = generateTemporaryPassword('Rai Roy');
    assert.ok(renamed.includes('Rai') && !renamed.includes('Dua'));
    assert.ok(new Set(sample('Dua Dino', 100)).size > 80, 'fresh digits and special position each time');
    assert.ok(first.includes('Dua') && again.includes('Dua'));
  });
});
describe('secure randomness', () => {
  const src = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'passwordGenerator.js'), 'utf8');

  it('uses Web Crypto only: no Math.random anywhere in the generator', () => {
    assert.ok(!src.includes('Math.random'));
    assert.ok(src.includes('getRandomValues'));
  });
  it('randomInt reads from the supplied crypto source and refuses to run without one', () => {
    let calls = 0;
    const fake = { getRandomValues: (b) => { calls += 1; b[0] = 7; return b; } };
    assert.equal(randomInt(5, fake), 2);
    assert.equal(calls, 1);
    assert.throws(() => randomInt(5, null), /Secure random/);
    assert.throws(() => randomInt(5, {}), /Secure random/);
  });
  it('randomInt discards values from the uneven tail (no modulo bias)', () => {
    const draws = [0xffffffff, 0xfffffffe, 3];
    let i = 0;
    const fake = { getRandomValues: (b) => { b[0] = draws[i++]; return b; } };
    assert.equal(randomInt(10, fake), 3);
    assert.equal(i, 3, 'two out-of-range draws were redrawn');
  });
  it('randomInt is roughly uniform (supplementary statistical check)', () => {
    const counts = Array(6).fill(0);
    for (let i = 0; i < 12000; i += 1) counts[randomInt(6)] += 1;
    assert.ok(counts.every((c) => c > 1700 && c < 2300), counts.join());
  });
});
describe('copyText', () => {
  it('copies via the Clipboard API and reports success', async () => {
    let written;
    assert.equal(await copyText('Secret@1234', { navigator: { clipboard: { writeText: async (t) => { written = t; } } } }), true);
    assert.equal(written, 'Secret@1234');
  });
  it('reports failure when the clipboard rejects and there is no fallback', async () => {
    assert.equal(await copyText('x', { navigator: { clipboard: { writeText: async () => { throw new Error('denied'); } } } }), false);
    assert.equal(await copyText('x', {}), false);
  });
  it('falls back to execCommand when the Clipboard API is missing', async () => {
    const calls = [];
    const area = { setAttribute() {}, style: {}, select() { calls.push('select'); } };
    const doc = { body: { appendChild() {}, removeChild() {} }, createElement: () => area, execCommand: (c) => { calls.push(c); return true; } };
    assert.equal(await copyText('abc', { document: doc }), true);
    assert.equal(area.value, 'abc');
    assert.deepEqual(calls, ['select', 'copy']);
  });
});

describe('Add New Staff modal wiring for the temporary password', () => {
  const dir = path.dirname(fileURLToPath(import.meta.url));
  const src = fs.readFileSync(path.join(dir, '../components/staff/AddStaffModal.jsx'), 'utf8').replace(/\r\n/g, '\n');

  it('regenerates live from the name only while the password is not custom', () => {
    assert.ok(src.includes("...(name === 'fullName' && !passwordLocked ? { temporaryPassword: value.trim() ? generateTemporaryPassword(value) : '' } : {}),"));
  });
  it('typing a password locks it (clearing hands it back); regenerating creates a fresh one and locks it', () => {
    assert.ok(src.includes("if (name === 'temporaryPassword') setPasswordLocked(value !== '');"));
    assert.ok(src.includes('const regeneratePassword = () => {') && src.includes('generateTemporaryPassword(prev.fullName)') && src.includes('aria-label="Regenerate password"'));
  });
  it('has a copy control with success and failure feedback, next to the unchanged eye toggle', () => {
    assert.ok(src.includes('aria-label="Copy password"') && src.includes('await copyText(formData.temporaryPassword)'));
    assert.ok(src.includes("'Copied'") && src.includes("'Copy failed'"));
    assert.ok(src.includes("showTemporaryPassword ? 'Hide password' : 'Show password'"));
  });
  it('keeps only the eye inside the input; copy and regenerate are two equal icon-only buttons beneath it', () => {
    const start = src.indexOf('label="Temporary Password *"');
    const block = src.slice(start, src.indexOf('</Field>', start));
    const input = block.slice(block.indexOf('id="add-staff-password"'));
    assert.ok(input.includes('pr-11') && input.includes("'Hide password' : 'Show password'") && !input.includes('aria-label="Copy password"'), 'only the eye is inside the input');
    const footer = block.slice(block.indexOf('footer={'), block.indexOf('id="add-staff-password"'));
    assert.ok(footer.includes('grid grid-cols-2 gap-2'), 'two equal columns');
    assert.equal(footer.match(/className=\{PASSWORD_ACTION_BUTTON\}/g).length, 2);
    assert.equal(footer.match(/type="button"/g).length, 2, 'never submits the form');
    assert.ok(footer.includes('aria-label="Copy password"') && footer.includes('title="Copy password"') && footer.includes('aria-label="Regenerate password"') && footer.includes('title="Regenerate password"'));
    assert.ok(footer.indexOf('Copy password') < footer.indexOf('Regenerate password'));
    assert.ok(!/>\s*(Copy|Regenerate)[^<{]*</.test(footer.replace(/aria-label="[^"]*"|title="[^"]*"/g, '')), 'no text labels inside the buttons');
    assert.ok(src.includes('focus-visible:ring-2') && src.includes("const timer = setTimeout(() => setCopyState('idle'), 2500);"), 'feedback clears itself');
  });  it('submits exactly the value in the field and never logs it', () => {
    assert.ok(src.includes('temporaryPassword: formData.temporaryPassword,'));
    assert.ok(!/console\.\w+\([^)]*[Pp]assword/.test(src));
  });
});
