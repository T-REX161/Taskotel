# Staff self-service task claiming (Option A)

A Staff member can accept an unassigned task of their own department themselves. This document is the short reference for that feature;
the access rules are in `RBAC_SPECIFICATION.md` (endpoint table).

## What users see

* **Tasks page, Staff only:** one card with two tabs, **My Tasks** (the person's assigned tasks, with the search and filters) and **Available**
  (tasks they may accept, with the count on the tab). Admin and Manager see the plain tasks table as before.
* **Available** lists the pending tasks of the person's own department that nobody holds, oldest first, each with an **Accept Task** button.
* A Staff member who already holds an active task still sees the list, with the reason and every button disabled. Staff who are inactive,
  have no department or are marked Unavailable get no tasks (the reason is shown instead).
* The summary cards count the person's own tasks only (`GET /api/tasks` returns the tasks assigned to them). Available tasks are not theirs
  until they accept one, so they are not in those totals.

## API

| Endpoint | Who | Result |
|---|---|---|
| `GET /api/tasks/available` | Staff | `{canClaim, reason, tasks}`; the tasks are the caller's department only |
| `POST /api/tasks/{id}/claim` | Staff | 200 with the task; 409 somebody was first, the task is no longer waiting, or the caller is busy or unavailable; 404 a task of another department, none, or a missing id; 403 inactive account or not Staff |

## Rules (Option A)

* One Staff member exclusively holds a task they claimed. The assignment row is marked `self_claimed = true`.
* Nobody else can claim it, a Manager's multi-staff assignment cannot add anyone to it, and Cross-Department Support never offers it for a
  helper and refuses a plan that names it.
* A Manager or Admin may still reassign or unassign it explicitly. A reassignment clears the mark (it becomes an ordinary assignment).
* Assignments made by a Manager or Admin, and every row that existed before V29, are `self_claimed = false` and behave exactly as before.
* The claim is decided on the database rows as they are under the task and staff row locks, so a change that commits while a claim waits
  (availability, department, deactivation, task cancelled or moved, a Manager assigning it) is honoured.

## Database

`V29__assignment_self_claimed.sql` adds one column, `assignments.self_claimed BOOLEAN NOT NULL DEFAULT FALSE`. It is additive, so the
previous backend keeps working on the migrated schema. **Apply it before the new backend starts:** production validates the schema
(`ddl-auto: validate`), so a backend without the column refuses to start. There is intentionally no V28 in this release. Rollback: first roll
the application back, then (optionally) `ALTER TABLE assignments DROP COLUMN self_claimed;`.

## Tests

Backend: `TaskClaimTest` (HTTP, real security chain, committed transactions: exclusivity, concurrent claims, locks, Manager reassignment,
multi-assignee tasks, busy staff), `SharedTaskSupportTest` (Cross-Department Support never offers a self-claimed task), `MigrationFilesTest`,
`SecurityConfigEndpointCoverageTest`. These run on H2; concurrency on PostgreSQL is not proven by them (see the release notes).
Frontend: `availableTasks.test.js`.
