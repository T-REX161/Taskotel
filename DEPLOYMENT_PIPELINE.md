# Taskotel deployment pipeline and release guide

Frontend: React + Vite on **Vercel**. Backend: Spring Boot on **Render** (Docker). Database: **Supabase PostgreSQL** (Session Pooler).
Source: GitHub `T-REX161/Taskotel`, integration branch `main`. This guide lists only variable **names**, never values.

## 1. What is confirmed and what you must check in the dashboards

The repository proves how each service is *built*. It cannot show a dashboard's settings. Check the "Dashboard" items once and write the answers in the last column.

| Question | From the repository (confirmed) | Dashboard check (not visible from the repo) | Your answer |
|---|---|---|---|
| Vercel production branch | GitHub default branch is `main`; PRs #4 and #5 were merged into it | Vercel > Project > Settings > Git > **Production Branch** (expected `main`) | |
| Render deployed branch | `DEPLOYMENT_RENDER.md` says "the branch you deploy from" | Render > Service > Settings > **Branch** (expected `main`) | |
| Auto-deploy after a merge into `main` | Nothing in the repo (no `.github`, no `render.yaml`) turns it on | Vercel: automatic for the Production Branch. Render: Settings > **Auto-Deploy** (On = "After CI checks pass"/"On Commit") | |
| Vercel root directory | `frontend/vercel.json` and `frontend/package.json` live in `frontend/` | Vercel > Settings > General > **Root Directory** must be `frontend` | |
| Vercel build | `npm run build` = `vite build`, output `dist`, Node `>=22` (`engines`) | Framework preset Vite; Build Command and Output Directory left on defaults; Node 22.x | |
| Render root / build / start | `backend/Dockerfile`: JDK 25 build stage, JRE 25 runtime, tests skipped in the image, `exec java $JAVA_OPTS -jar /app/app.jar`, profile `prod` | Render > Settings: **Root Directory** `backend`, Language **Docker**, Dockerfile `./Dockerfile`. There is **no separate Build or Start command**: the Dockerfile is both | |
| Render health check path | `/actuator/health` (public, `{"status":"UP"}`) and `/healthz` both exist | Render > Settings > **Health Check Path** | |

## 2. How the two services relate

* **Independent deployments.** Vercel builds `frontend/`, Render builds `backend/`. A merge that changes only one folder still triggers
  a build of both services unless "Ignored Build Step" (Vercel) or "Build Filters / Root Directory" (Render) is configured. A rebuild with
  unchanged code is harmless, only slower.
* **Coupling points:** (1) `frontend/vercel.json` rewrites `/api/*` to `https://taskotel.onrender.com/api/*`, so the browser stays on one
  origin and no CORS is needed. If the Render service URL ever changes, update `vercel.json`. (2) The frontend and backend share the API
  contract: a release that changes an endpoint's shape must keep the **old frontend working** until both are live (backend first).
* **Preview deployments.** Vercel also builds every other branch as a *preview* with its own URL (protected by Vercel login). A preview
  shows only that branch's code and is not production. The same branch can look "old" if it has not been updated (this happened with
  `deploy/vercel-config`).
* **Render cold start.** Render may stop an idle instance; the first request can take over a minute and the frontend then shows "A server
  error occurred". Wait and retry before suspecting a bug.

## 3. Release steps (normal change)

1. **Branch and develop** from an up-to-date `main`: `git switch main && git pull && git switch -c feature/<name>`.
2. **Test locally.** Frontend (`frontend/`): `npm test`, `npm run lint`, `npm run build`. Backend (`backend/`): `./mvnw clean test`.
3. **Push the branch and open a Pull Request into `main`.** Vercel posts a preview URL on the PR: check the UI there. Resolve conflicts on the branch.
4. **If the change adds a database migration, follow section 4 before merging.**
5. **Merge the PR into `main`.** Vercel deploys production automatically. Render deploys if Auto-Deploy is on; otherwise press
   **Manual Deploy > Deploy latest commit** in Render.
6. **Deploy order when both change:** backend first (Render, wait until healthy), then frontend. The frontend must never require an
   endpoint that is not live yet.
7. **Verify** (section 5).
8. Do **not** edit production data or environment variables as part of a normal release.

## 4. Database migrations (Flyway, V1 to V24 today)

Facts: schema changes live in `backend/src/main/resources/db/migration` (`V<n>__name.sql`). Production uses `baseline-on-migrate: false` and
`ddl-auto: validate`: Hibernate never changes the schema and refuses to start if the code and schema differ. Flyway runs at startup **only
when `WB_FLYWAY_ENABLED=true`**. It is currently **false** in production, so a new migration will **not** be applied by a plain deploy and the
new backend would fail validation on start.

Safe procedure for a release with a new migration:

1. **Never edit an already-applied migration** (its checksum is stored). Add a new `V25__...sql`, `V26__...`, in order.
2. Test it on an empty local PostgreSQL and on a copy of production-like data (`mvn -Pmigration-it test` runs against rehearsal databases only).
3. Make it **backward compatible** (add columns/tables, do not drop or rename in the same release) so the previous backend still runs if you roll back.
4. **Back up first.** Supabase dashboard > Database > Backups (or a `pg_dump -Fc`), and note the time.
5. Enable migrations for this release: set `WB_FLYWAY_ENABLED=true` in Render and deploy the backend. Watch the logs for
   `Successfully applied 1 migration` and `Started WorkloadBalancerApplication`.
6. Check the history (read-only): `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;` every row `success = true`.
7. Decide whether to leave `WB_FLYWAY_ENABLED=true` permanently (recommended by `DEPLOYMENT_RENDER.md`, so future releases apply migrations
   automatically) or set it back to `false` after each migration release. Pick one policy and keep to it. Never run with `false` while the
   code expects a newer schema.
8. A migration that fails mid-way can leave a failed row in the history; do not retry blindly. Restore from the backup, or repair under supervision.

## 5. Verify a deployment

* **Vercel:** Deployments tab shows the latest **Production** deployment with the expected commit hash (`git rev-parse --short main`) and
  status *Ready*. Open the production domain, hard-refresh (Ctrl+F5), and check the changed screen.
* **Render:** Events/Logs show the build finishing, then `Started WorkloadBalancerApplication`, no Flyway or validation error, and the
  service status *Live*.
* **API:** `curl https://taskotel.onrender.com/actuator/health` returns `{"status":"UP"}` (HTTP 200). This does **not** query the database.
* **Proxy and routes:** `curl -i <production-domain>/api/workload` without a token returns **401** (backend reached through the rewrite).
* **End to end:** sign in as an Admin, open Dashboard and Workload, confirm no error banner; a sign-in plus any list proves the database.

## 6. Roll back

* **Frontend (Vercel):** Deployments > pick the last good Production deployment > **Promote to Production** (or the "Instant Rollback"
  option). It is immediate and does not touch the database. Then revert the bad commit on `main` with a new PR.
* **Backend (Render):** Events/Deploys > choose the previous successful deploy > **Rollback**. Or revert the commit on `main` and redeploy.
* **Database:** application rollbacks do **not** undo migrations. This is why migrations are backward compatible (section 4, step 3): the
  old backend keeps working on the newer schema. Only if data was damaged, restore the pre-release backup, which loses newer data; that is
  a deliberate, supervised action.
* **Environment variable mistake:** restore the previous value in the dashboard and redeploy that service.
* After any rollback, repeat the checks in section 5 and tell the team which commit is live.

## 7. Do not

* Do not commit secrets, `.env` files or passwords. Do not paste credentials into chat or tickets.
* Do not enable demo data in production, and do not edit `flyway_schema_history` by hand.
* Do not merge directly to `main` without a PR, and do not force-push `main`.

## 8. Known gaps in the current setup

* `DEPLOYMENT_RENDER.md` has been updated for Supabase and the bootstrap-based first Admin.
* CI: `.github/workflows/ci.yml` runs the backend tests, the frontend tests, lint and build on every pull request and every push to `main`
  (it never deploys). Make the checks required in GitHub (Settings > Branches) if merges should be blocked on a failure.
* The Docker image build was verified on Render. TLS to the database uses `sslmode=require` (encrypts, does not verify the certificate).
