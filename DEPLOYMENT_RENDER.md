# Deploying the Taskotel backend on Render

This document describes the **backend** (Spring Boot 4, Java 25) on Render with an external **Supabase PostgreSQL** database (Session Pooler)
and the **Vercel** frontend. (It was first written for a Neon database; production now runs on Supabase, and this file has been updated to
match.) No credential appears in this file: only variable **names** are listed, and every value is supplied in the Render dashboard.
For the release routine, migrations and rollbacks see `DEPLOYMENT_PIPELINE.md`.

## 1. Architecture

```
Browser ──► Vercel (React frontend) ──► Render Web Service (Docker, Spring Boot) ──► Supabase PostgreSQL
                                                  ├──► SendGrid   (verification, reset and notification emails)
                                                  └──► Cloudinary (profile pictures)
```

* The backend is stateless (JWT bearer tokens, no server session), so a single instance is enough and a restart loses nothing but
  in-memory rate-limit counters and any queued notification emails.
* Flyway creates and upgrades the schema; Hibernate only **validates** it (`ddl-auto: validate`).

## 2. Files added for deployment

| File | Purpose |
|---|---|
| `backend/Dockerfile` | Multi-stage build: JDK 25 + the project's Maven wrapper, then a JRE 25 runtime that runs as a non-root user |
| `backend/.dockerignore` | Keeps `.env` files, `target/`, Git data, IDE files, logs, tests and local DB tools out of the build context |
| `backend/src/main/resources/application.yml` | `server.port=${PORT:8080}`; `taskotel.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:}` |
| `backend/src/main/resources/application-prod.yml` (and its `production` twin) | Datasource from the environment with TLS, small connection pool, graceful shutdown, no error details in responses |
| `backend/.../config/CorsConfig.java` | CORS for the Vercel origin(s) only; off unless `CORS_ALLOWED_ORIGINS` is set |
| `backend/.../common/HealthController.java` | `GET /healthz` liveness probe |

Local development is unchanged: the default profile still uses `localhost:8080`, the Vite proxy and `backend/.env`.

## 3. Render service settings

| Setting | Value |
|---|---|
| Service type | **Web Service** |
| Runtime / Language | **Docker** |
| Repository / Branch | `T-REX161/Taskotel`, the branch you deploy from |
| **Root Directory** | `backend` |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | `.` (the root directory, `backend`) |
| **Health Check Path** | `/healthz` |
| Auto-Deploy | Off for the first deployment, on afterwards if you want it |
| Instance | At least **512 MB**; a JVM plus Spring Boot is tight there, so prefer a larger plan if you see out-of-memory restarts |
| `PORT` | Set by Render itself. Do **not** define it; the application listens on it |

### Docker details
* Build stage `eclipse-temurin:25-jdk`; `mvnw` is converted from CRLF to LF (it is committed with Windows line endings), dependencies
  are cached in their own layer, then `mvnw clean package` runs with tests skipped (run `mvn clean test` before releasing; see section 10).
* Runtime stage `eclipse-temurin:25-jre`, user `taskotel` (not root), `SPRING_PROFILES_ACTIVE=prod` by default,
  `JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"`, started with `exec java $JAVA_OPTS -jar /app/app.jar`.
* No credential is written into any image layer. Everything secret is an environment variable at run time.

## 4. Environment variables (names only)

### Required
| Name | Meaning |
|---|---|
| `DB_HOST` | Database host. For Supabase use the **Session Pooler** host (`aws-0-<region>.pooler.supabase.com`); see section 6. Not needed if `DB_URL` is set |
| `DB_NAME` | Database name (default `workload_balancer` if omitted; Supabase uses `postgres`, so it must be set) |
| `DB_USER` | Database user. The application reads `DB_USER` (not `DB_USERNAME`). On the Supabase pooler it must include the project reference, `postgres.<project-ref>`, or the pooler answers `no tenant identifier provided` |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Base64 text that decodes to **at least 32 bytes**. The application refuses to start otherwise. Generate one with `openssl rand -base64 48` |
| `SENDGRID_API_KEY` | SendGrid key with the Mail Send permission. Startup fails in production if it is missing or a placeholder |
| `SENDGRID_FROM_EMAIL` | The verified Single Sender / authenticated-domain address. Startup fails if it is missing or has no `@` |
| `WB_FLYWAY_ENABLED` | Set to `true` (see section 5). Flyway is off by default, and the production profile validates the schema, so an empty database would fail without it |
| `FRONTEND_BASE_URL` | The Vercel URL, used for the sign-in link in invitation emails (default is the local frontend) |
| `CORS_ALLOWED_ORIGINS` | Comma-separated **exact** origins, for example `https://taskotel.vercel.app`. See section 8 |

### Optional
| Name | Default | Meaning |
|---|---|---|
| `DB_URL` | built from the parts above | A complete JDBC URL; when set it is used as given |
| `DB_PORT` | `5432` | |
| `DB_SSLMODE` | `require` | TLS mode in the built URL (managed PostgreSQL requires TLS) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` / `DB_POOL_CONNECTION_TIMEOUT_MS` | `5` / `1` / `30000` | Connection pool for a small deployment |
| `SPRING_PROFILES_ACTIVE` | `prod` (set in the image) | `production` also works and loads the same settings |
| `JWT_EXPIRATION` | `86400000` | Token lifetime in milliseconds |
| `SENDGRID_FROM_NAME` | `Taskotel` | Sender display name |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | empty | All three enable profile pictures. Without them the application still starts and uploads are simply unavailable |
| `JAVA_OPTS` | see above | JVM options |
| `NOTIFICATIONS_ASYNC`, `NOTIFICATIONS_QUEUE_CAPACITY`, `TEMPORARY_PASSWORD_LIFETIME_HOURS`, `LOGIN_RATE_LIMIT_*`, ... | see `application.yml` | Existing tuning knobs, unchanged |
| `AUTH_CLIENT_ADDRESS_TRUSTED_PROXIES` | empty | See the rate-limit note in section 11 |

Never set `DEMO_DATA_ENABLED`: demo data never runs under the production profile anyway.

## 5. Flyway migration behaviour

* The schema is built by `backend/src/main/resources/db/migration` (V1 to V24). On an **empty** database Flyway runs them in order; V4 also
  inserts the six built-in departments, and `DepartmentBootstrap`/`DemoDataSeeder` do not run in production.
* In production `baseline-on-migrate` is `false`: a **non-empty** database without Flyway history makes startup fail instead of being adopted
  silently. A new managed database starts empty, so this is the intended behaviour.
* `WB_FLYWAY_ENABLED=true` makes Flyway run at startup (it is `false` by default). Production was built from an empty database with it on and
  then switched off; **with it off, a release that adds a migration fails startup validation**. Set it to `true` for such a release (see
  `DEPLOYMENT_PIPELINE.md`, section 4) and decide one standing policy.
* After Flyway, Hibernate validates every entity against the schema (`ddl-auto: validate`). A mismatch stops startup with a clear message
  instead of changing the database.
* The migrations have been applied to the production Supabase database (V1 to V24, all successful). The repository also has PostgreSQL
  migration tests (`mvn -Pmigration-it test`, against rehearsal databases only). Rehearse any new migration on a disposable database first.
* Departments: V4 seeds the six starting departments once. An Admin can later remove **any** department (built-in or not) that no staff,
  task or assignment uses, and a removed department is never re-created: production does not re-seed, and `DepartmentBootstrap` (outside
  production only) seeds a brand-new, empty database and nothing else.

## 6. Supabase PostgreSQL (Session Pooler)

1. Note the pooler host, port, database (`postgres`), the user (`postgres.<project-ref>`) and the database password. Do not paste them into
   Git or chat.
2. Use the **Session Pooler** on port **5432**. (Port 6543 is transaction mode and is not used.) Flyway and the Hikari pool (small, 5)
   both work with session mode.
3. TLS is required; the default `sslmode=require` is right. It encrypts the connection but does not verify the server certificate.
4. **Turn the Supabase Data API off** (Project Settings, Data API). Taskotel's tables live in the `public` schema without row-level
   security, so the public REST API would otherwise expose them. The application connects directly and does not use the Data API.
5. Data in a local PostgreSQL database **does not move to Supabase automatically**. A first start with `WB_FLYWAY_ENABLED=true` gives an
   empty schema plus the six starting departments.

## 7. First administrator

Production never creates accounts by itself, so a new database has **nobody who can sign in**, and no clear password may be written into
SQL, Git or chat. The first Admin is created once with a reviewed, parameterised bootstrap that inserts exactly one `ADMIN` user and its four
notification-preference rows (`TASK_ASSIGNED`, `TASK_STATUS`, `WORKLOAD_ALERT`, `TASK_REMINDER`) in one transaction, after strict checks
(the schema matches, Flyway is at the last version with every migration successful, `users` and `notification_preferences` are empty). The
account is created with a random, unknown BCrypt hash, `email_verified = false` and `credentials_setup_required = true`.

The Admin then sets a password through the application itself, with no password ever shown to anyone else:
1. On the login page choose **Forgot password** and enter the Admin's email: a reset code arrives by email (SendGrid must be configured).
2. Enter the code and choose a password (at least 8 characters with upper case, lower case, a digit and a special character).
3. Sign in: a verification code is emailed; entering it opens the credentials-setup page, where the username and password are confirmed.

Create every other account from the application. (The reviewed bootstrap scripts are not stored in this repository. If another environment
needs one, add them under review rather than writing an ad-hoc INSERT.)

## 8. CORS and the frontend on Vercel

* `CORS_ALLOWED_ORIGINS` accepts exact origins only (scheme, host, optional port; a trailing slash is ignored). A wildcard, a path or a
  wrong scheme stops startup. Credentials are not used (the token travels in the `Authorization` header). Only `/api/**` is covered.
* CORS grants no access by itself: an unauthenticated request is still refused with 401 and every role rule is unchanged.
* **Important, not changed here:** the frontend calls relative URLs such as `/api/auth/login` (in development the Vite proxy forwards
  them). On Vercel those calls would go to Vercel, not to Render. Before the frontend is deployed, choose one of:
  1. a Vercel rewrite from `/api/:path*` to `https://<your-service>.onrender.com/api/:path*` (the browser then stays on one origin and CORS is
     not even needed), or
  2. a build-time API base URL in the frontend (a frontend code change), which needs `CORS_ALLOWED_ORIGINS`.

## 9. Deployment procedure

1. **Prepare secrets** in a password manager: database credentials, `JWT_SECRET`, SendGrid key and verified sender, Cloudinary values.
2. **Dry run** (recommended, no production data): create a disposable database and start the application locally against it
   with the production variables and `WB_FLYWAY_ENABLED=true`; confirm Flyway applies V1 to V24 and the application starts. Delete it after.
3. **Create the Render Web Service** with the settings in section 3 and set every variable of section 4. Leave Auto-Deploy off.
4. **Deploy.** Watch the logs for `Started WorkloadBalancerApplication`, Flyway applying the migrations, and no startup validation error.
5. **Health check:** open `https://<service>.onrender.com/healthz`; expect `{"status":"UP"}`.
6. **Create the first administrator** (section 7), then sign in through the frontend or a test request.
7. **Check the email flow** with a test staff account (verification code by SendGrid) and a profile picture upload (Cloudinary).
8. Switch **Auto-Deploy** on if you want it, and keep `WB_FLYWAY_ENABLED=true`.

## 10. Health checks and verification before release

* `/healthz` is public, sits outside `/api`, returns no data, and **does not query the database**: it says the process is serving. A failed
  database connection stops the application from starting, which Render then reports. For a deeper check, sign in and call `/api/dashboard/summary`.
* Before releasing, run the backend tests from `backend/`: `./mvnw clean test` (about 1,850 tests, H2, no external service), then `./mvnw clean package`.
  The Docker build skips tests only to stay fast; it does not replace them.

## 11. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `JWT signing secret ... must be a valid Base64-encoded string` or `insufficient` | `JWT_SECRET` is missing, not Base64, or shorter than 32 decoded bytes. Regenerate with `openssl rand -base64 48` |
| `Production startup failed: ... SENDGRID_API_KEY` / `SENDGRID_FROM_EMAIL` | Missing, blank, a `test-` placeholder, or the sender has no `@` |
| `Schema-validation: missing table` | Flyway did not run: set `WB_FLYWAY_ENABLED=true` on the first start |
| `Found non-empty schema(s) ... without schema history table` | The target database is not empty. Use a fresh database; the production profile deliberately does not baseline |
| `The connection attempt failed` / `connection timed out` | Wrong host or port, or the database is waking. Retry after a few seconds; check `DB_HOST` and `DB_PORT` |
| `FATAL: (ENOTIDENTIFIER) no tenant identifier provided` | Supabase pooler without the project reference: set `DB_USER` (not `DB_USERNAME`) to `postgres.<project-ref>` |
| The first request after a quiet period fails with "A server error occurred" | A Render instance that spins down needs more than a minute to wake and the proxy times out. See `DEPLOYMENT_PIPELINE.md` (cold starts) |
| `FATAL: no pg_hba.conf entry ... SSL` or `SSL required` | Keep `DB_SSLMODE=require` (the default) |
| `CORS_ALLOWED_ORIGINS must list exact origins` | A wildcard, path or non-http(s) entry; fix the value |
| Browser shows a CORS error | The Vercel origin is not in `CORS_ALLOWED_ORIGINS` exactly (scheme and host must match; no trailing path) |
| Everyone is blocked after a few sign-in attempts | Behind Render's proxy every user shares one client address, so the per-client limits apply together. Raise the `LOGIN_RATE_LIMIT_PER_CLIENT*` values, or configure `AUTH_CLIENT_ADDRESS_TRUSTED_PROXIES` with Render's proxy addresses (verify them first; a wrong range lets callers choose their own identity) |
| Container killed / restarts | Out of memory: use a larger instance, or lower `-XX:MaxRAMPercentage` in `JAVA_OPTS` |
| Cannot sign in at all on a new database | No administrator exists yet: section 7 |
| Profile picture upload unavailable | The three `CLOUDINARY_*` variables are not all set |

## 12. Security precautions

* All credentials come from environment variables; none are in Git, the image, the logs or this file. `.env` files are Git-ignored and excluded from the Docker context.
* Production runs the strict profile: Hibernate validates only, SQL logging is off, demo data and the `/api/test/**` probes do not exist, and
  responses never contain exception messages, stack traces or binding details.
* The application refuses to start with a missing or weak `JWT_SECRET` or a placeholder SendGrid key. Passwords are hashed with BCrypt.
* Authorization is deny-by-default: every `/api` route and method has an explicit role rule, and the services re-check scope from the database.
* Keep the SendGrid key to the Mail Send permission only, and rotate `JWT_SECRET` if it is ever exposed (all sessions then end).
* Use a dedicated database role for the application, and never expose the database credentials to the frontend.
* Logs mask email addresses and never print keys or tokens. Do not turn on request-body logging in front of the service.
