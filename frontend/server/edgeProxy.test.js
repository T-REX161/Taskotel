import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  ASSERTION_HEADER,
  BACKEND_ORIGIN,
  buildAssertion,
  createEdgeHandler,
  sign,
  visitorAddress,
} from './edgeProxy.js';

const SECRET = '0123456789abcdef0123456789abcdef';
const NOW = 1_700_000_000_000; // 1700000000 s

/** A request/response pair shaped like the Node objects Vercel passes to a function. */
function call(handlerOptions, { method = 'POST', headers = {}, body } = {}) {
  const sent = [];
  const fetchImpl = handlerOptions.fetchImpl ?? (async (url, init) => {
    sent.push({ url, init });
    return new Response('{"token":"t"}', { status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'x=1', 'x-secret': 'leak' } });
  });
  const handler = createEdgeHandler({ method: 'POST', path: '/api/auth/login', env: { TASKOTEL_EDGE_SECRET: SECRET }, now: () => NOW, ...handlerOptions, fetchImpl });
  const res = { statusCode: 0, headers: {}, body: '', setHeader(k, v) { this.headers[k.toLowerCase()] = v; }, end(b) { this.body = b; } };
  const req = { method, headers, get body() { if (body instanceof Error) throw body; return body; } };
  return handler(req, res).then(() => ({ res, sent }));
}

describe('signature (shared test vector with ClientAddressResolver)', () => {
  it('produces the exact value the Java side verifies', () => {
    assert.equal(sign(SECRET, '1700000000', '198.51.100.7'), '15ceb925a087baa66655b4f59d61461e01b6430902b34e7003f59935937539da');
    assert.equal(buildAssertion(SECRET, '198.51.100.7', NOW), `1700000000;198.51.100.7;${sign(SECRET, '1700000000', '198.51.100.7')}`);
  });

  it('is bound to the address and the time', () => {
    assert.notEqual(sign(SECRET, '1700000000', '198.51.100.8'), sign(SECRET, '1700000000', '198.51.100.7'));
    assert.notEqual(sign(SECRET, '1700000001', '198.51.100.7'), sign(SECRET, '1700000000', '198.51.100.7'));
  });

  it('gives no assertion without a usable secret or address', () => {
    assert.equal(buildAssertion(undefined, '198.51.100.7', NOW), null);
    assert.equal(buildAssertion('', '198.51.100.7', NOW), null);
    assert.equal(buildAssertion('too-short', '198.51.100.7', NOW), null);
    assert.equal(buildAssertion(SECRET, null, NOW), null);
  });
});

describe('visitor address', () => {
  it('uses the last X-Forwarded-For entry, so an entry the visitor typed in front is never used', () => {
    assert.equal(visitorAddress({ 'x-forwarded-for': '203.0.113.9' }), '203.0.113.9');
    assert.equal(visitorAddress({ 'x-forwarded-for': '1.2.3.4, 5.6.7.8' }), '5.6.7.8');
    assert.equal(visitorAddress({ 'x-forwarded-for': '2001:db8::1' }), '2001:db8::1');
  });

  it('never uses X-Real-IP or any other header: without X-Forwarded-For there is no visitor address', () => {
    assert.equal(visitorAddress({ 'x-real-ip': '203.0.113.9' }), null);
    assert.equal(visitorAddress({ 'true-client-ip': '203.0.113.9', 'cf-connecting-ip': '203.0.113.9', forwarded: 'for=203.0.113.9' }), null);
    assert.equal(visitorAddress({ 'x-forwarded-for': '', 'x-real-ip': '203.0.113.9' }), null);
    assert.equal(visitorAddress({ 'x-forwarded-for': ' , ', 'x-real-ip': '203.0.113.9' }), null);
  });

  it('a spoofed X-Real-IP cannot override a valid X-Forwarded-For, nor stand in for an invalid one', () => {
    assert.equal(visitorAddress({ 'x-forwarded-for': '198.51.100.1', 'x-real-ip': '6.6.6.6' }), '198.51.100.1');
    assert.equal(visitorAddress({ 'x-forwarded-for': 'not-an-address', 'x-real-ip': '6.6.6.6' }), null);
    assert.equal(visitorAddress({ 'x-forwarded-for': '6.6.6.6, bad', 'x-real-ip': '198.51.100.1' }), null);
  });

  it('refuses anything that is not an address', () => {
    for (const bad of ['', 'evil.example.com', '999.1.1.1', '1.2.3.4:80', '1.2.3.4, evil', '<script>']) {
      assert.equal(visitorAddress({ 'x-forwarded-for': bad }), null, bad);
    }
    assert.equal(visitorAddress({}), null);
  });
});

describe('the edge handler', () => {
  it('forwards a login to the fixed backend path with a signed visitor address', async () => {
    const { res, sent } = await call({}, {
      headers: { 'content-type': 'application/json', 'x-forwarded-for': '198.51.100.7', 'user-agent': 'ua' },
      body: { username: 'zapcom', password: 'p' },
    });
    assert.equal(res.statusCode, 200);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].url, `${BACKEND_ORIGIN}/api/auth/login`);
    assert.equal(sent[0].init.method, 'POST');
    assert.equal(sent[0].init.body, '{"username":"zapcom","password":"p"}');
    assert.equal(sent[0].init.headers[ASSERTION_HEADER], buildAssertion(SECRET, '198.51.100.7', NOW));
    assert.equal(sent[0].init.redirect, 'manual');
  });

  it('keeps different visitors apart', async () => {
    const a = await call({}, { headers: { 'x-forwarded-for': '198.51.100.1' }, body: {} });
    const b = await call({}, { headers: { 'x-forwarded-for': '198.51.100.2' }, body: {} });
    assert.match(a.sent[0].init.headers[ASSERTION_HEADER], /;198\.51\.100\.1;/);
    assert.match(b.sent[0].init.headers[ASSERTION_HEADER], /;198\.51\.100\.2;/);
  });

  it('never forwards what the visitor must not control: forged assertion, cookies, credentials, forwarding headers', async () => {
    const { sent } = await call({}, {
      headers: {
        'x-forwarded-for': '198.51.100.7',
        'x-taskotel-client': '1;6.6.6.6;deadbeef',
        'X-Taskotel-Client': 'forged',
        cookie: 'session=1',
        authorization: 'Bearer abc',
        'x-real-ip': '6.6.6.6',
        'true-client-ip': '6.6.6.6',
        forwarded: 'for=6.6.6.6',
        'content-type': 'application/json',
      },
      body: {},
    });
    const forwarded = sent[0].init.headers;
    assert.deepEqual(Object.keys(forwarded).sort(), [ASSERTION_HEADER, 'content-type'].sort());
    assert.match(forwarded[ASSERTION_HEADER], /;198\.51\.100\.7;/);
  });

  it('sends no assertion when the secret is not configured (the backend then uses its own view, as before)', async () => {
    for (const env of [{}, { TASKOTEL_EDGE_SECRET: '' }, { TASKOTEL_EDGE_SECRET: 'short' }]) {
      const { res, sent } = await call({ env }, { headers: { 'x-forwarded-for': '198.51.100.7' }, body: {} });
      assert.equal(res.statusCode, 200);
      assert.equal(sent[0].init.headers[ASSERTION_HEADER], undefined);
    }
  });

  it('sends no assertion when no valid visitor address is available', async () => {
    const { sent } = await call({}, { headers: { 'x-forwarded-for': 'not-an-address' }, body: {} });
    assert.equal(sent[0].init.headers[ASSERTION_HEADER], undefined);
  });

  it('a missing X-Forwarded-For produces no signed assertion, but the request is still forwarded (unsigned)', async () => {
    const { res, sent } = await call({}, { headers: { 'content-type': 'application/json' }, body: { username: 'u', password: 'p' } });
    assert.equal(res.statusCode, 200);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].init.headers[ASSERTION_HEADER], undefined);
  });

  it('X-Real-IP alone cannot establish the client identity, and is not forwarded either', async () => {
    const { res, sent } = await call({}, { headers: { 'x-real-ip': '198.51.100.7', 'true-client-ip': '198.51.100.7' }, body: {} });
    assert.equal(res.statusCode, 200);
    assert.equal(sent[0].init.headers[ASSERTION_HEADER], undefined);
    assert.equal(sent[0].init.headers['x-real-ip'], undefined);
  });

  it('a spoofed X-Real-IP cannot override a valid X-Forwarded-For: the signed address is the X-Forwarded-For one', async () => {
    const { sent } = await call({}, { headers: { 'x-forwarded-for': '198.51.100.7', 'x-real-ip': '6.6.6.6' }, body: {} });
    assert.equal(sent[0].init.headers[ASSERTION_HEADER], buildAssertion(SECRET, '198.51.100.7', NOW));
    assert.doesNotMatch(sent[0].init.headers[ASSERTION_HEADER], /6\.6\.6\.6/);
  });

  it('refuses other methods without calling the backend', async () => {
    const { res, sent } = await call({}, { method: 'GET', headers: {} });
    assert.equal(res.statusCode, 405);
    assert.equal(res.headers.allow, 'POST');
    assert.equal(sent.length, 0);
  });

  it('refuses a malformed or oversized body without calling the backend', async () => {
    const bad = await call({}, { body: new SyntaxError('bad json') });
    assert.equal(bad.res.statusCode, 400);
    assert.equal(bad.sent.length, 0);
    const big = await call({}, { body: { x: 'a'.repeat(70_000) } });
    assert.equal(big.res.statusCode, 413);
    assert.equal(big.sent.length, 0);
  });

  it('passes the backend status, Retry-After and body through (a 429 stays a 429) and drops other response headers', async () => {
    const fetchImpl = async () => new Response('{"message":"Too many sign-in attempts. Please wait a few minutes and try again."}', {
      status: 429,
      headers: { 'content-type': 'application/json', 'retry-after': '120', 'set-cookie': 'a=b', 'x-powered-by': 'x' },
    });
    const { res } = await call({ fetchImpl }, { headers: {}, body: {} });
    assert.equal(res.statusCode, 429);
    assert.equal(res.headers['retry-after'], '120');
    assert.equal(res.headers['content-type'], 'application/json');
    assert.equal(res.headers['set-cookie'], undefined);
    assert.equal(res.headers['x-powered-by'], undefined);
    assert.match(res.body, /Too many sign-in attempts/);
  });

  it('reports an unreachable backend as 502 and a timeout as 504, without details or the secret', async () => {
    const down = await call({ fetchImpl: async () => { throw new TypeError('fetch failed: connect ECONNREFUSED 10.0.0.1'); } }, { headers: {}, body: {} });
    assert.equal(down.res.statusCode, 502);
    const slow = await call({ fetchImpl: async () => { throw Object.assign(new Error('t'), { name: 'TimeoutError' }); } }, { headers: {}, body: {} });
    assert.equal(slow.res.statusCode, 504);
    for (const { res } of [down, slow]) {
      assert.ok(!res.body.includes('ECONNREFUSED') && !res.body.includes(SECRET));
    }
  });

  it('does not follow a backend redirect', async () => {
    const { sent } = await call({}, { headers: {}, body: {} });
    assert.equal(sent[0].init.redirect, 'manual');
  });
});
