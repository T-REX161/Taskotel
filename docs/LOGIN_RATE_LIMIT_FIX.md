# Login "Too many sign-in attempts" on production: cause, fix and rollout

## Cause

All traffic reaches the backend through Vercel's rewrite and Render's proxy, so the application sees **one connection address for every
visitor**. `ClientAddressResolver` correctly refuses to believe `X-Forwarded-For` (any caller can send it), so every visitor is the same
"client" for `AttemptLimiter`. Its keys are `client|account` (5), `client` (20) and `account` (30) per 15 minutes, so:

* 20 wrong passwords by anybody (a typo, a bot scanning the public login page) block **every** login, including correct ones;
* 5 wrong guesses at one account by anybody block that account's real owner.

The lockout is in memory and a sliding window: it clears by itself after 15 minutes and on every restart or redeploy, which is why it is
intermittent. Reproduced through the real endpoint by `LoginSharedProxyRootCauseTest`.

## Fix

A small Vercel function fronts only the public authentication endpoints. Vercel supplies the visitor's address to the function; the
function forwards the request to the backend with `X-Taskotel-Client: <unix seconds>;<address>;<HMAC-SHA256>`, signed with a secret shared
with the backend. The backend believes the address **only** for a valid, fresh (120 s) signature. Everything else, including every
forwarding header and every direct call to `onrender.com`, keeps being identified by the connection address exactly as before.

* `backend/.../ClientAddressResolver.java`: verifies the assertion. Off unless `AUTH_CLIENT_ADDRESS_EDGE_SECRETS` is set.
* `frontend/server/edgeProxy.js` and `frontend/api/edge-auth-*.js`: the six functions (login, credentials setup, password-reset request and
  confirm, e-mail-verification send and verify).
* `frontend/vercel.json`: six specific rewrites placed **before** the existing catch-all. The existing rules are unchanged.
* `frontend/eslint.config.js`: the two new folders are linted as Node code.

Thresholds, window, `AttemptLimiter` and account rules are unchanged.

## Rollout (production; nothing here has been done)

0. Check once: Vercel Project, Settings, General, **Root Directory** is `frontend` (the functions live in `frontend/api`).
1. Generate the secret into a password manager: `openssl rand -base64 48`. Never type it into a shell history, chat or a ticket.
2. **Backend first.** Render, production service, Environment: add `AUTH_CLIENT_ADDRESS_EDGE_SECRETS` = the secret. Deploy the backend
   (a version containing this change). With no function yet sending the header nothing changes; confirm a normal login works.
3. Vercel, Settings, Environment Variables: add `TASKOTEL_EDGE_SECRET` = the same secret, **Production** scope (add **Preview** only if
   you want previews signed too), mark **Sensitive**, no `VITE_` prefix. Environment variable changes apply to new deployments only.
4. Deploy the frontend (merge to `main`, or Promote). Optionally first open the pull request and test its automatic Vercel preview; a preview
   without the variable simply forwards without a signature (today's behaviour).
5. Smoke test, in this order:
   * Sign in with a normal account: succeeds. Also run the other public pages once (forgot password request, e-mail verification code).
   * From your own network, make 6 wrong attempts with a **made-up username** such as `ratelimit-probe` (never a real account): five `401`,
     then `429`. In the Render logs the line `Login attempts from client <address> are rate limited` must show **your public address**, not
     the proxy's private one.
   * From another network (phone on mobile data), a normal login still works while your first network is blocked.
6. Rollback if anything misbehaves: Vercel, Deployments, previous production deployment, **Instant Rollback** (restores the plain rewrite);
   the backend can stay as it is (without a signed header it behaves as before). To switch the feature off completely remove
   `AUTH_CLIENT_ADDRESS_EDGE_SECRETS` and redeploy.
7. Rotation: set Render to `new,old`, change Vercel to `new` and redeploy, then drop `old` from Render.

## What was assumed from documentation and could not be tested here

* A rewrite listed before the catch-all wins and may point at a function (documented: "the first rewrite that matches"), and the rewritten
  function path is not matched again by `/api/:path*`.
* A function receives the visitor's address in `x-forwarded-for` (Vercel overwrites it) or `x-real-ip`; the function uses the **last**
  `x-forwarded-for` entry, so a value the visitor typed can never be chosen.
* A classic `(request, response)` function receives a parsed JSON `request.body`.
* `frontend/api` is picked up with Root Directory `frontend`.

If the first rewrite or function misbehaves, login fails with 404 or 502 instead of working; use step 6.
