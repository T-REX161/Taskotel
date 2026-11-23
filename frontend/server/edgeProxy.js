// Server-side proxy for the PUBLIC authentication endpoints (login, first-password setup, password reset, e-mail verification).
//
// Why it exists: with a plain Vercel rewrite every visitor reaches the backend through the same proxy, so the backend's per-client rate limits
// see one "client" and one noisy caller blocks everybody. This function runs on Vercel, where the visitor's address is supplied by Vercel
// itself, and forwards the request to the backend with that address in a SIGNED header. The backend believes the address only when the
// signature is valid (see ClientAddressResolver); anything else is identified by the connection's own address, as before.
//
// This file runs only on the server (it is imported by /api/edge-*.js, never by the browser bundle). The secret comes from the
// TASKOTEL_EDGE_SECRET environment variable of the Vercel project and is never written to a response, a log or a header.
import { createHmac } from 'node:crypto';
import net from 'node:net';

export const BACKEND_ORIGIN = 'https://taskotel.onrender.com';
export const ASSERTION_HEADER = 'X-Taskotel-Client';
export const ASSERTION_CONTEXT = 'taskotel-client-v1';
export const MIN_SECRET_LENGTH = 32;
const TIMEOUT_MS = 120_000; // a sleeping Render instance can need more than a minute to wake up
const MAX_BODY_BYTES = 64 * 1024; // these endpoints take small JSON bodies
// Only these request headers reach the backend. Cookies, Authorization, every forwarding header and any X-Taskotel-Client the visitor sent are dropped.
const FORWARD_REQUEST_HEADERS = ['content-type', 'accept', 'accept-language', 'user-agent'];
const FORWARD_RESPONSE_HEADERS = ['content-type', 'retry-after', 'cache-control'];

/** HMAC-SHA256 (lower-case hex) over the context line, the unix seconds and the address, one per line. Must match ClientAddressResolver.sign. */
export function sign(secret, seconds, address) {
  return createHmac('sha256', secret).update(`${ASSERTION_CONTEXT}\n${seconds}\n${address}`).digest('hex');
}

/**
 * The visitor's address as Vercel reports it: the LAST X-Forwarded-For entry (Vercel overwrites the header with the visitor's address; if an
 * entry were ever appended instead, the last one is the one Vercel's own edge wrote), else X-Real-IP. Null when neither is a valid address.
 */
export function visitorAddress(headers) {
  const forwarded = String(headers['x-forwarded-for'] ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const candidate = forwarded.length > 0 ? forwarded[forwarded.length - 1] : String(headers['x-real-ip'] ?? '').trim();
  return net.isIP(candidate) !== 0 ? candidate : null;
}

/** The header value for a request, or null when there is no usable secret or address (the backend then uses the connection's address). */
export function buildAssertion(secret, address, nowMs) {
  if (typeof secret !== 'string' || secret.trim().length < MIN_SECRET_LENGTH || !address) return null;
  const seconds = String(Math.floor(nowMs / 1000));
  return `${seconds};${address};${sign(secret.trim(), seconds, address)}`;
}

function readBody(req) {
  let body;
  try {
    body = req.body; // Vercel parses the body lazily; a malformed JSON body throws here
  } catch {
    return { error: 400 };
  }
  if (body === undefined || body === null || body === '') return { text: '' };
  const text = Buffer.isBuffer(body) ? body.toString('utf8') : typeof body === 'string' ? body : JSON.stringify(body);
  if (Buffer.byteLength(text) > MAX_BODY_BYTES) return { error: 413 };
  return { text };
}

function reply(res, status, json) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(json));
}

/**
 * A Vercel function (request, response) that forwards one fixed endpoint to the backend. `method` and `path` are fixed per function file,
 * so a visitor cannot choose where a request goes. Dependencies are parameters so the tests can replace them.
 */
export function createEdgeHandler({ method, path, backend = BACKEND_ORIGIN, fetchImpl = (...a) => fetch(...a), env = process.env, now = Date.now }) {
  return async function handler(req, res) {
    if (req.method !== method) {
      res.setHeader('Allow', method);
      return reply(res, 405, { message: 'Method not allowed' });
    }
    const body = readBody(req);
    if (body.error) return reply(res, body.error, { message: body.error === 413 ? 'Request too large' : 'Bad request' });

    const headers = {};
    for (const name of FORWARD_REQUEST_HEADERS) {
      if (typeof req.headers[name] === 'string') headers[name] = req.headers[name];
    }
    const assertion = buildAssertion(env.TASKOTEL_EDGE_SECRET, visitorAddress(req.headers), now());
    if (assertion) headers[ASSERTION_HEADER] = assertion;

    let upstream;
    try {
      upstream = await fetchImpl(`${backend}${path}`, {
        method,
        headers,
        body: body.text === '' ? undefined : body.text,
        redirect: 'manual',
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch (err) {
      const timedOut = err && (err.name === 'TimeoutError' || err.name === 'AbortError');
      return reply(res, timedOut ? 504 : 502, { message: 'The service is temporarily unavailable. Please try again.' });
    }

    res.statusCode = upstream.status;
    for (const name of FORWARD_RESPONSE_HEADERS) {
      const value = upstream.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    res.end(await upstream.text());
  };
}
