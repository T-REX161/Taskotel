# Task Reminders

Email reminders about tasks that are about to be due or are overdue. Controlled per user by the **Task Reminders** switch
(`TASK_REMINDER`) in Settings > Notification Preferences.

**Off by default.** `taskotel.reminders.enabled=false` (env `TASKOTEL_REMINDERS_ENABLED`). While it is false no scheduler is created and
nothing is sent.

## Deployment order (read this before deploying)

Two migrations belong to this work, and both are applied **by hand** (Flyway is off in production unless `WB_FLYWAY_ENABLED=true`):

| Migration | Adds | Needed by |
|---|---|---|
| `V26__assignment_assigned_by.sql` | `assignments.assigned_by_user_id` (nullable) | the assignment and completion emails |
| `V27__task_reminder_log.sql` | the `task_reminder_log` table | task reminders |

1. **Apply V26, then V27**, each with `psql -1 -v ON_ERROR_STOP=1 -f <file>`, after review and a rehearsal on a disposable database.
2. **Then** deploy the new backend.
3. **Then**, only when you want reminders, set `taskotel.reminders.enabled=true` and restart.

**Both migrations must be applied before the new backend starts, even while reminders are switched off.** Production runs Hibernate schema
validation (`ddl-auto: validate`), and both the new column and the reminder log table are mapped whether or not the feature is on. A backend
started without them **fails at startup**. (An old backend keeps working against a database that already has V26 and V27: both only add.)

Nothing in this change applies a migration for you, and the application never changes production data on its own.

## Rollback order

Roll back the **application first**, and only then consider reverting the migrations:

1. Roll the backend back to the previous version (or set `taskotel.reminders.enabled=false` and restart, if you only want to stop reminders).
2. Only then, if you really need to, run the rollback in the migration's header (`DROP TABLE task_reminder_log;` and
   `ALTER TABLE assignments DROP COLUMN assigned_by_user_id;`).

Dropping either while the new backend is still deployed makes it fail its schema validation on the next start (and, for V26, breaks every
assignment query). Dropping the reminder log also erases the overdue counts and reminder history: a re-enabled feature would start clean and
a task could be reminded again.

## Time zone

Every reminder calculation uses **Asia/Kolkata**, explicitly, whatever zone the server runs in (production runs in UTC). A task's `dueDate`
and `deadlineTime` are read as wall-clock values in that zone.

## Schedule

The scheduler runs every 15 minutes, on the quarter hours (IST).

| Task | Reminder | When |
|---|---|---|
| Has a deadline time | Due in 24 hours | 24 h before the deadline |
| Has a deadline time | Due in 2 hours | 2 h before the deadline |
| Date only | Due today | 09:00 on the due date |
| Has a deadline time | Overdue, first | At the deadline |
| Has a deadline time | Overdue, daily | 09:00 each following day |
| Date only | Overdue, first | 09:00 the day after the due date |
| Date only | Overdue, daily | 09:00 each following day |

* At most **3 overdue emails per task, per person** (the first one included). Changing the task's due date or time starts a fresh count.
* A reminder is sent only while its moment is at most `grace-minutes` (default 30, two runs) in the past. Older reminders are **never** sent
  late: a task created or assigned after a reminder's moment does not receive it.
* An assignee is only sent a reminder if they were **already assigned when its moment arrived** (`assignments.assigned_at`, read in the server's
  zone). Someone assigned five minutes after the deadline does not receive that deadline's overdue email, even inside the grace window.
* Someone assigned to an already-overdue task receives the **next** daily overdue reminder (not the missed ones), and then counts toward
  their own cap of three.

### Configuration is validated at startup

`grace-minutes` must be at least as long as the **longest gap between two scheduler runs** (`taskotel.reminders.cron`). Otherwise a reminder
moment could fall between two runs and never be sent, with no error. When reminders are enabled the application **refuses to start** with a
message saying what to change, for example `grace-minutes=10 is shorter than the longest gap between scheduler runs (15 minutes ...)`.
An invalid cron expression or a grace period below 1 is refused too. The defaults (15-minute schedule, 30-minute grace) are valid and leave room
for one failed or retried run. Nothing is validated while the feature is off.

## Who is reminded

Recipients are read from the database at the time of sending.

| Situation | Due-soon / due-today | Overdue |
|---|---|---|
| Task is assigned | Each current assignee | Each current assignee and the department's Manager(s) |
| Task is not assigned (PENDING) | The department's Manager(s), "not assigned to anyone yet" | The department's Manager(s) |

* Department Managers are resolved by the existing rule: primary-department Managers plus Managers with an active grant for that department,
  valid accounts only. A Manager of an unrelated department is never reminded.
* Completed, cancelled and deleted tasks are never reminded, including when they finished after the reminder was claimed but before it was sent.
* A reassigned task reminds the new holder; the previous holder stops being a recipient.
* Several assignees each get their own reminder. A person with several tasks due in one run gets **one email** listing them.
* Each recipient's `TASK_REMINDER` preference and active/verified account checks apply.

### Accounts that share an email address

Each account's eligibility and preference are decided **first and separately**. The eligible accounts that share one address (any letter case)
are then served by **one email** that combines their reminders (a task both were reminded about appears once). An account that opted out is
never merged in, and its reminders are not shown to the others. Every merged account is recorded with the outcome of that one email, so an
account is never marked `SKIPPED` merely because another account with the same address came first.

## Delivery guarantee

**Best effort, at most once. Not guaranteed delivery.**

1. A reminder is first **claimed**: a row is inserted into `task_reminder_log` (unique per task, recipient, kind, slot and due moment). Two
   runs, threads or application instances cannot claim the same reminder, so it is never sent twice. A claim is **not** a delivery record.
2. The job goes to the existing notification queue (bounded, in memory). The worker then re-checks, **right before sending**, that the task
   is still open with the same due date, that the recipient is still valid, and that the reminder's moment is **still inside its window at the
   current time** (not the time it was claimed). A backed-up queue therefore cannot deliver a stale reminder.
3. Each row ends with exactly one outcome, written per row (one failed write cannot affect another):

| Status | Meaning | Counts toward the overdue cap |
|---|---|---|
| `SENT` | SendGrid accepted the request. Not proof an inbox received it. | yes |
| `FAILED` | SendGrid **confirmed a refusal** (an HTTP error answer) or email was not configured: nothing was sent. | no |
| `UNKNOWN` | A broken or timed-out connection after the request may have been received, or the worker never reported back. **It may have been sent.** | yes |
| `SKIPPED` | Deliberately not sent: recipient not eligible or opted out, or the task finished or changed. | no |
| `EXPIRED` | The reminder's window had closed before the worker reached it, so it was not sent late. | no |
| `CLAIMED` | Reserved, outcome not recorded yet. | yes |

4. If the queue **rejects** the job (full or shutting down), or claiming stops part-way because of a database error, the claims of **that run
   only** are deleted again (and only while still `CLAIMED`), so a later tick may try. The reminders already claimed are queued, not stranded.
   A reminder is only retried while its moment is still inside the grace window; after that it is dropped.
5. Nothing is retried after an outcome is recorded. A row that is still `CLAIMED` an hour later (a crash or stop, a dropped job, or a log
   write that failed **after** the email was accepted) becomes `UNKNOWN`, never `FAILED`, and keeps counting toward the cap, so a lost log
   write can never lead to a fourth overdue email.
6. No email is sent while a database transaction or lock is held. A failure never affects any task operation.

A claim that fails *at commit* (the database answered an error but may have written the row) can leave a `CLAIMED` row that no job belongs to;
it becomes `UNKNOWN` after an hour. That is the conservative choice and is accepted.

## Operating notes

* If email is not configured, a run claims and sends nothing.
* The candidate query loads every open task with a due date up to tomorrow on each run. That is fine for hundreds or a few thousand
  tasks; revisit it (a lower date bound, or pre-filtering by the log) if the task table grows much larger.
* **Log retention (future consideration, not implemented).** `task_reminder_log` only grows: accounts that keep opting out or being skipped
  add rows for every overdue day. Do **not** delete history casually: the log is what enforces the cap of three overdue emails and prevents
  repeats, so a purge that removes rows of tasks still overdue could reset the cap or allow a duplicate. A safe purge would remove only rows
  of tasks that are completed, cancelled or deleted (deleting a task already cascades its rows), or older than a window that is far past any
  possible reminder. Decide that rule deliberately before building it.
* Staff self-acceptance (a planned feature) must not be recorded as the task's assigner (`assignments.assigned_by_user_id`); give it its own
  field. Reminders do not use the assigner at all.
