import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const config = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'));

const EXPECTED = {
  '/api/auth/login': ['POST', '/api/auth/login'],
  '/api/auth/credentials/setup': ['PUT', '/api/auth/credentials/setup'],
  '/api/auth/password-reset/request': ['POST', '/api/auth/password-reset/request'],
  '/api/auth/password-reset/confirm': ['POST', '/api/auth/password-reset/confirm'],
  '/api/auth/email-verification/send': ['POST', '/api/auth/email-verification/send'],
  '/api/auth/email-verification/verify': ['POST', '/api/auth/email-verification/verify'],
};

describe('vercel.json with the edge functions', () => {
  const rewrites = config.rewrites;
  const iCatchAll = rewrites.findIndex((r) => r.source === '/api/:path*');

  it('keeps the original rules unchanged: the /api rewrite to the backend, the SPA fallback and the security headers', () => {
    assert.deepEqual(rewrites[iCatchAll], { source: '/api/:path*', destination: 'https://taskotel.onrender.com/api/:path*' });
    assert.deepEqual(rewrites[iCatchAll + 1], { source: '/((?!api/).*)', destination: '/index.html' });
    assert.equal(rewrites.length, Object.keys(EXPECTED).length + 2);
    assert.deepEqual(config.headers, [{
      source: '/(.*)',
      headers: [
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'X-Frame-Options', value: 'DENY' },
        { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
      ],
    }]);
    for (const key of ['routes', 'redirects', 'cleanUrls', 'trailingSlash', 'functions']) assert.equal(config[key], undefined, key);
  });

  it('lists the specific endpoints BEFORE the catch-all (Vercel applies the first rewrite that matches)', () => {
    const specific = rewrites.slice(0, iCatchAll);
    assert.deepEqual(specific.map((r) => r.source), Object.keys(EXPECTED));
  });

  it('sends each public authentication endpoint to a function file that exists and forwards exactly that method and path', () => {
    for (const rule of rewrites.slice(0, iCatchAll)) {
      const file = path.join(root, `${rule.destination}.js`);
      assert.ok(fs.existsSync(file), rule.destination);
      const text = fs.readFileSync(file, 'utf8');
      const [method, backendPath] = EXPECTED[rule.source];
      assert.ok(text.includes(`method: '${method}'`) && text.includes(`path: '${backendPath}'`), rule.destination);
      assert.match(text, /^import \{ createEdgeHandler \} from '\.\.\/server\/edgeProxy\.js';/);
    }
  });

  it('has no other function files and no literal secret anywhere in the configuration or the function files', () => {
    const files = fs.readdirSync(path.join(root, 'api'));
    assert.equal(files.length, Object.keys(EXPECTED).length);
    const everything = [JSON.stringify(config), ...files.map((f) => fs.readFileSync(path.join(root, 'api', f), 'utf8'))].join('\n');
    assert.doesNotMatch(everything, /(?=[A-Za-z0-9+_-]*[0-9])(?=[A-Za-z0-9+_-]*[A-Z])(?=[A-Za-z0-9+_-]*[a-z])[A-Za-z0-9+_-]{32,}/);
  });
});

describe('the secret stays out of the browser bundle sources', () => {
  function sourceFiles(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) return sourceFiles(p);
      return /\.(jsx?|css)$/.test(e.name) && !e.name.endsWith('.test.js') ? [p] : [];
    });
  }

  it('no file under src/ or index.html mentions the secret variable or the assertion header', () => {
    for (const file of [...sourceFiles(path.join(root, 'src')), path.join(root, 'index.html')]) {
      assert.ok(!/TASKOTEL_EDGE_SECRET|X-Taskotel-Client|edgeProxy/i.test(fs.readFileSync(file, 'utf8')), file);
    }
  });

  it('the function files are not reachable from the browser code (nothing in src/ imports server/ or api/)', () => {
    for (const file of sourceFiles(path.join(root, 'src'))) {
      assert.ok(!/from\s+['"][./]*\/(server|api)\//.test(fs.readFileSync(file, 'utf8')), file);
    }
  });

  it('the environment variable is not VITE_-prefixed (a VITE_ variable would be compiled into the browser bundle)', () => {
    assert.ok(!/VITE_[A-Z_]*SECRET/.test(fs.readFileSync(path.join(root, 'server', 'edgeProxy.js'), 'utf8')));
  });
});
