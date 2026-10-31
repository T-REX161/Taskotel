import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(__dirname, 'EmailVerificationPage.jsx'), 'utf8');
const app = fs.readFileSync(path.join(__dirname, '..', 'App.jsx'), 'utf8');

// The success branch of the verify handler: everything from the `data.setupToken` check to the end of its else block
const successBranch = source.match(/if \(data\.setupToken\) \{[\s\S]*?\n {8}\} else \{[\s\S]*?\n {8}\}\n/)?.[0] || '';
const [setupBranch = '', loginBranch = ''] = successBranch.split(/\n {8}\} else \{/);

describe('Email verification success navigation', () => {
  it('reads the setup token from the "setupToken" property the backend returns', () => {
    assert.ok(successBranch, 'success branch found');
    assert.match(source, /response\.ok && data\?\.success/);
  });

  it('goes straight to /set-credentials, passing the token in router state, when a setup token is returned', () => {
    assert.match(setupBranch, /navigate\('\/set-credentials'/);
    assert.match(setupBranch, /setupToken: data\.setupToken/);
    assert.doesNotMatch(setupBranch, /navigate\('\/login'/);
  });

  it('returns to login only in the branch where no setup token was returned (password setup not required)', () => {
    assert.match(loginBranch, /navigate\('\/login'/);
    assert.doesNotMatch(loginBranch, /set-credentials/);
    assert.equal((source.match(/Email verified successfully\. Please sign in to continue\./g) || []).length, 1);
  });

  it('keeps the existing set-credentials route and never stores the setup token outside router state', () => {
    assert.match(app, /path="\/set-credentials" element=\{<SetCredentialsPage \/>\}/);
    assert.doesNotMatch(source, /localStorage\.setItem\([^)]*setupToken/);
    assert.doesNotMatch(source, /sessionStorage/);
  });
});
