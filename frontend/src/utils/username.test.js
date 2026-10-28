import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { generateUsername, validateUsername, candidateUsernames, GENERATED_MAX_LENGTH, USERNAME_MAX_LENGTH } from './username.js';

describe('generateUsername', () => {
  it('joins the lowercase name parts', () => {
    assert.equal(generateUsername('Ayush Mallick'), 'ayushmallick');
    assert.equal(generateUsername('Rahul Sharma'), 'rahulsharma');
    assert.equal(generateUsername('Priya Das'), 'priyadas');
  });

  it('removes spaces, punctuation and accents, and never produces an email address', () => {
    assert.equal(generateUsername("  Mary-Jane   O'Neil  "), 'maryjaneoneil');
    assert.equal(generateUsername('José Álvarez'), 'josealvarez');
    assert.equal(generateUsername('a.b@hotel.com'), 'abhotelcom');
    assert.ok(!/[\s@]/.test(generateUsername('Some Person @ Work')));
  });

  it('caps the length and returns nothing for names too short or without usable characters', () => {
    assert.equal(generateUsername('x'.repeat(80)).length, GENERATED_MAX_LENGTH);
    assert.equal(generateUsername('Al'), '');
    assert.equal(generateUsername(''), '');
    assert.equal(generateUsername(null), '');
    assert.equal(generateUsername('李 雷'), '');
  });
});

describe('validateUsername', () => {
  it('accepts usable usernames and says why others cannot be checked', () => {
    assert.equal(validateUsername('ayushmallick'), 'ok');
    assert.equal(validateUsername('  padded  '), 'ok');
    assert.equal(validateUsername(''), 'empty');
    assert.equal(validateUsername('   '), 'empty');
    assert.equal(validateUsername('two words'), 'space');
    assert.equal(validateUsername('x'.repeat(USERNAME_MAX_LENGTH + 1)), 'long');
  });
});

describe('candidateUsernames', () => {
  it('offers first.last and first_last for a generated name, then numbered variants, without the original', () => {
    const list = candidateUsernames('ayushmallick', 'Ayush Mallick');
    assert.deepEqual(list.slice(0, 3), ['ayush.mallick', 'ayush_mallick', 'ayushmallick1']);
    assert.ok(!list.includes('ayushmallick'));
    assert.equal(new Set(list).size, list.length, 'no duplicates');
  });

  it('uses only numbered variants for a username typed by hand', () => {
    assert.deepEqual(candidateUsernames('nina', 'Someone Else', 3), ['nina1', 'nina2', 'nina3']);
  });

  it('keeps every alternative within the length limit', () => {
    const base = 'y'.repeat(USERNAME_MAX_LENGTH);
    for (const candidate of candidateUsernames(base, '')) {
      assert.ok(candidate.length <= USERNAME_MAX_LENGTH && candidate !== base);
    }
  });
});
