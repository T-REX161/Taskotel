-- Preflight report: READ-ONLY schema inspection of the Taskotel database. Prints catalog metadata, counts and
-- booleans only; never record contents, emails, password hashes or credentials.
--
-- It covers the seven application tables (assignments, departments, notification_preferences, staff,
-- support_assignments, tasks, users), Flyway history, and the state that migrations V1 to V14 depend on. It reports
-- what it OBSERVES and never assumes the database matches the migration scripts: a table or column that is
-- missing shows up as absent (false / NULL), it does not stop the report.
--
-- Run (PowerShell) with read-only enforced for the whole session, restoring any previous values after:
--   $savedPassword = $env:PGPASSWORD; $savedOptions = $env:PGOPTIONS
--   try {
--     $env:PGOPTIONS = '-c default_transaction_read_only=on'
--     $env:PGPASSWORD = $env:DB_PASSWORD
--     & 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h localhost -U postgres -d workload_balancer -w -X -v ON_ERROR_STOP=1 -f preflight.sql
--   } finally {
--     if ($null -eq $savedPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue } else { $env:PGPASSWORD = $savedPassword }
--     if ($null -eq $savedOptions) { Remove-Item Env:PGOPTIONS -ErrorAction SilentlyContinue } else { $env:PGOPTIONS = $savedOptions }
--   }
--
-- Section S0 must show transaction_read_only = on, and S14 must still show on at the end.
--
-- Row counts are taken with query_to_xml('SELECT count(*) ...'), which only runs for tables that exist, so a missing
-- table gives NULL instead of an error. Application rows are only ever counted, never selected.
--
-- How to read it for the migrations:
--   V3  (S8): uk_name_uses = 0, task_id uuid / not null / one validated FK to tasks(id), 0 unique indexes on
--             task_id, tasks_with_duplicate_assignments = 0.
--   V4  (S9): departments absent before V4; the two enum CHECKs on staff.department and tasks.department are what V4
--             drops; every staff/task department code must exist in departments before V4 adds its foreign keys.
--   V5/V6 (S10), V7-V11/V13/V14 (S11), V12 (S12): each object is reported present or absent, with its definition.
\pset pager off
\pset footer off

\echo '=== S0. Session: read-only enforcement, server, schema resolution'
SELECT current_database() AS db, current_user AS db_user,
       current_setting('transaction_read_only') AS transaction_read_only,
       current_setting('default_transaction_read_only') AS default_read_only,
       current_setting('server_version') AS server_version,
       current_schema() AS current_schema, current_setting('search_path') AS search_path,
       (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS is_superuser;

\echo '=== S1. Non-system schemas and their tables (Flyway inspects the default schema)'
SELECT n.nspname AS schema, count(c.oid) FILTER (WHERE c.relkind IN ('r','p')) AS tables
FROM pg_namespace n LEFT JOIN pg_class c ON c.relnamespace = n.oid
WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast') AND n.nspname NOT LIKE 'pg_temp%' AND n.nspname NOT LIKE 'pg_toast_temp%'
GROUP BY n.nspname ORDER BY 1;

\echo '=== S2. The seven application tables: present or absent, owner, exact row count (counts only)'
SELECT e.name AS table,
       (c.oid IS NOT NULL) AS present,
       pg_get_userbyid(c.relowner) AS owner,
       CASE WHEN c.oid IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.' || quote_ident(e.name), false, true, '')))[1]::text::bigint
       END AS row_count
FROM (VALUES ('assignments'), ('departments'), ('notification_preferences'), ('staff'), ('support_assignments'), ('tasks'), ('users')) e(name)
LEFT JOIN pg_class c ON c.relname = e.name AND c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r','p')
ORDER BY e.name;
\echo '--- every table in public that is NOT one of the seven (expected: only flyway_schema_history, or nothing)'
SELECT c.relname AS unexpected_table FROM pg_class c
WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r','p')
  AND c.relname NOT IN ('assignments','departments','notification_preferences','staff','support_assignments','tasks','users')
ORDER BY 1;

\echo '=== S3. Flyway history table anywhere? (and its rows: version metadata only)'
SELECT n.nspname AS schema, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname ILIKE '%schema_history%' OR c.relname ILIKE 'flyway%';
SELECT (to_regclass('public.flyway_schema_history') IS NOT NULL) AS flyway_history_present,
       CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.flyway_schema_history', false, true, '')))[1]::text::bigint
       END AS history_rows,
       CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN NULL
            ELSE (xpath('/row/v/text()', query_to_xml('SELECT max(installed_rank)::text AS v FROM public.flyway_schema_history', false, true, '')))[1]::text
       END AS highest_installed_rank;

\echo '=== S4. Columns of the seven tables: type, length, nullability, default'
SELECT table_name AS table, ordinal_position AS pos, column_name AS column,
       CASE WHEN character_maximum_length IS NOT NULL THEN data_type || '(' || character_maximum_length || ')'
            WHEN datetime_precision IS NOT NULL AND data_type LIKE 'time%' THEN data_type || ' p' || datetime_precision
            ELSE data_type END AS type,
       is_nullable AS nullable, coalesce(column_default, '') AS "default"
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('assignments','departments','notification_preferences','staff','support_assignments','tasks','users')
ORDER BY table_name, ordinal_position;

\echo '=== S5. Constraints: PK / UNIQUE / FK (with ON DELETE / ON UPDATE) / CHECK (definitions are schema text only)'
SELECT conrelid::regclass AS table, conname AS name,
       CASE contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' WHEN 'f' THEN 'FOREIGN KEY' WHEN 'c' THEN 'CHECK' ELSE contype::text END AS kind,
       pg_get_constraintdef(oid) AS definition,
       CASE WHEN contype = 'f' THEN CASE confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END END AS on_delete,
       CASE WHEN contype = 'f' THEN CASE confupdtype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END END AS on_update,
       convalidated AS validated, condeferrable AS deferrable
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY conrelid::regclass::text, contype, conname;

\echo '=== S6. Indexes (all, public schema)'
SELECT tablename AS table, indexname AS index, indexdef AS definition
FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename, indexname;

\echo '=== S7. V1 objects and V2 column state'
SELECT i.name AS v1_index, EXISTS (SELECT 1 FROM pg_class WHERE relname = i.name AND relnamespace = 'public'::regnamespace) AS present
FROM (VALUES ('idx_users_username'),('idx_users_email'),('idx_staff_user_id'),('idx_staff_email'),
             ('idx_tasks_created_by'),('idx_tasks_status'),('idx_assignments_task_id'),('idx_assignments_staff_id')) i(name);
SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'credentials_setup_required';
SELECT CASE WHEN to_regclass('public.users') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.users WHERE credentials_setup_required IS NULL', false, true, '')))[1]::text::bigint
       END AS null_credentials_setup_required;

\echo '=== S8. V3 prerequisites: name collision, assignments.task_id, existing unique index, duplicates'
SELECT 'pg_constraint' AS catalog, connamespace::regnamespace::text AS schema, conname AS name, contype::text AS kind
FROM pg_constraint WHERE conname = 'uk_assignments_task_id'
UNION ALL
SELECT 'pg_class', relnamespace::regnamespace::text, relname, relkind::text FROM pg_class WHERE relname = 'uk_assignments_task_id';
SELECT (SELECT count(*) FROM pg_constraint WHERE conname = 'uk_assignments_task_id')
     + (SELECT count(*) FROM pg_class WHERE relname = 'uk_assignments_task_id') AS uk_name_uses;
WITH a AS (SELECT attnum, format_type(atttypid, atttypmod) AS type, attnotnull FROM pg_attribute
           WHERE attrelid = to_regclass('public.assignments') AND attname = 'task_id' AND NOT attisdropped)
SELECT (SELECT type FROM a) AS task_id_type,
       (SELECT attnotnull FROM a) AS task_id_not_null,
       (SELECT string_agg(conname || ' -> ' || confrelid::regclass || '(' ||
               (SELECT string_agg(attname, ',') FROM pg_attribute WHERE attrelid = confrelid AND attnum = ANY(confkey)) || ')'
               || ' validated=' || convalidated, '; ')
          FROM pg_constraint WHERE conrelid = to_regclass('public.assignments') AND contype = 'f'
           AND conkey = ARRAY[(SELECT attnum FROM a)]) AS task_id_foreign_keys,
       (SELECT count(*) FROM pg_index WHERE indrelid = to_regclass('public.assignments') AND indisunique
           AND indnkeyatts = 1 AND indkey[0] = (SELECT attnum FROM a) AND indpred IS NULL) AS unique_indexes_on_task_id,
       (SELECT count(*) FROM pg_index WHERE indrelid = to_regclass('public.assignments') AND indisunique
           AND (SELECT attnum FROM a) = ANY(indkey::int2[])) AS unique_indexes_including_task_id;
SELECT CASE WHEN to_regclass('public.assignments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM (SELECT task_id FROM public.assignments GROUP BY task_id HAVING count(*) > 1) d', false, true, '')))[1]::text::bigint
       END AS tasks_with_duplicate_assignments,
       CASE WHEN to_regclass('public.assignments') IS NULL OR to_regclass('public.tasks') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.assignments a WHERE NOT EXISTS (SELECT 1 FROM public.tasks t WHERE t.id = a.task_id)', false, true, '')))[1]::text::bigint
       END AS orphan_task_refs,
       CASE WHEN to_regclass('public.assignments') IS NULL OR to_regclass('public.staff') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.assignments a WHERE NOT EXISTS (SELECT 1 FROM public.staff s WHERE s.id = a.staff_id)', false, true, '')))[1]::text::bigint
       END AS orphan_staff_refs;

\echo '=== S9. V4 state: departments, built-in codes, department references, enum CHECKs, foreign keys, indexes'
SELECT b.code AS built_in_code,
       CASE WHEN to_regclass('public.departments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM public.departments WHERE code = %L', b.code), false, true, '')))[1]::text::bigint
       END AS rows_with_this_code
FROM (VALUES ('HOUSEKEEPING'), ('FRONT_DESK'), ('MAINTENANCE'), ('ROOM_SERVICE'), ('FOOD_AND_BEVERAGE'), ('SECURITY')) b(code);
SELECT CASE WHEN to_regclass('public.departments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.departments WHERE active', false, true, '')))[1]::text::bigint
       END AS active_departments,
       CASE WHEN to_regclass('public.departments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.departments WHERE head_staff_id IS NOT NULL', false, true, '')))[1]::text::bigint
       END AS departments_with_head_staff,
       CASE WHEN to_regclass('public.staff') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(DISTINCT department) AS c FROM public.staff', false, true, '')))[1]::text::bigint
       END AS distinct_staff_department_codes,
       CASE WHEN to_regclass('public.tasks') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(DISTINCT department) AS c FROM public.tasks', false, true, '')))[1]::text::bigint
       END AS distinct_task_department_codes;
SELECT CASE WHEN to_regclass('public.staff') IS NULL OR to_regclass('public.departments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.staff s WHERE s.department IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.departments d WHERE d.code = s.department)', false, true, '')))[1]::text::bigint
       END AS staff_codes_missing_from_departments,
       CASE WHEN to_regclass('public.tasks') IS NULL OR to_regclass('public.departments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.tasks t WHERE t.department IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.departments d WHERE d.code = t.department)', false, true, '')))[1]::text::bigint
       END AS task_codes_missing_from_departments;
\echo '--- CHECK constraints on staff / tasks that mention department (V4 drops the Hibernate enum checks)'
SELECT conrelid::regclass AS table, conname AS name, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE contype = 'c' AND conrelid IN (to_regclass('public.staff'), to_regclass('public.tasks'))
  AND pg_get_constraintdef(oid) ~* '\mdepartment\M'
ORDER BY 1, 2;
\echo '--- V4 foreign keys and indexes (present or absent)'
SELECT n.name AS v4_object, n.kind,
       CASE n.kind WHEN 'constraint' THEN EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = n.name)
                   ELSE EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = n.name) END AS present
FROM (VALUES ('uk_departments_code','constraint'), ('fk_departments_head_staff','constraint'), ('fk_staff_department_code','constraint'),
             ('fk_tasks_department_code','constraint'), ('uk_departments_name_lower','index'),
             ('idx_staff_department','index'), ('idx_tasks_department','index')) n(name, kind);

\echo '=== S10. V5/V6/V7 state: support_assignments constraints, indexes, archive columns'
SELECT n.name AS object, n.kind,
       CASE n.kind WHEN 'constraint' THEN EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = n.name)
                   ELSE EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = n.name) END AS present
FROM (VALUES ('uk_support_assignments_task','constraint'), ('ck_support_assignments_status','constraint'), ('support_assignments_status_check','constraint'),
             ('ck_support_assignments_end_reason','constraint'), ('support_assignments_end_reason_check','constraint'),
             ('idx_support_assignments_staff_status','index'), ('idx_support_assignments_task_status','index'), ('idx_support_assignments_plan','index'),
             ('uk_support_assignments_active_staff','index'), ('uk_support_assignments_active_task','index')) n(name, kind);
SELECT conname AS check_name,
       (pg_get_constraintdef(oid) LIKE '%MANUALLY_ENDED%') AS allows_manually_ended,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE contype = 'c' AND conrelid = to_regclass('public.support_assignments')
ORDER BY conname;
SELECT CASE WHEN to_regclass('public.support_assignments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.support_assignments WHERE status = ''ACTIVE''', false, true, '')))[1]::text::bigint
       END AS active_support_assignments,
       CASE WHEN to_regclass('public.support_assignments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM (SELECT task_id FROM public.support_assignments WHERE status = ''ACTIVE'' GROUP BY task_id HAVING count(*) > 1) d', false, true, '')))[1]::text::bigint
       END AS tasks_with_more_than_one_active_support,
       CASE WHEN to_regclass('public.support_assignments') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM (SELECT staff_id FROM public.support_assignments WHERE status = ''ACTIVE'' GROUP BY staff_id HAVING count(*) > 1) d', false, true, '')))[1]::text::bigint
       END AS staff_with_more_than_one_active_support;

\echo '=== S11. Columns added by V7-V11, V13 and V14: present or absent, type, nullability'
SELECT w.migration, w.tbl AS table, w.col AS column,
       (c.column_name IS NOT NULL) AS present, c.data_type, c.character_maximum_length AS length, c.is_nullable AS nullable
FROM (VALUES ('V7','support_assignments','archived_at'), ('V7','support_assignments','archived_by_username'),
             ('V8','users','profile_image_url'), ('V8','users','profile_image_public_id'),
             ('V9','users','display_name'),
             ('V10','users','last_login_at'), ('V10','users','password_changed_at'),
             ('V11','users','theme_preference'), ('V11','users','date_format'), ('V11','users','time_format'),
             ('V13','users','pending_email'), ('V13','users','pending_email_code_hash'), ('V13','users','pending_email_expires_at'),
             ('V13','users','pending_email_attempt_count'), ('V13','users','pending_email_last_sent_at'),
             ('V14','users','phone')) w(migration, tbl, col)
LEFT JOIN information_schema.columns c ON c.table_schema = 'public' AND c.table_name = w.tbl AND c.column_name = w.col
ORDER BY w.migration, w.tbl, w.col;

\echo '=== S12. V12 state: notification_preferences constraints and user references'
SELECT n.name AS object, n.kind,
       CASE n.kind WHEN 'constraint' THEN EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = n.name)
                   ELSE EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname = n.name) END AS present
FROM (VALUES ('uk_notification_preferences_user_category','constraint'), ('ck_notification_preferences_category','constraint')) n(name, kind);
SELECT conname AS foreign_key, pg_get_constraintdef(oid) AS definition,
       CASE confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END AS on_delete
FROM pg_constraint WHERE contype = 'f' AND conrelid = to_regclass('public.notification_preferences');
SELECT CASE WHEN to_regclass('public.notification_preferences') IS NULL OR to_regclass('public.users') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.notification_preferences p WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p.user_id)', false, true, '')))[1]::text::bigint
       END AS notification_preferences_without_user,
       CASE WHEN to_regclass('public.notification_preferences') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(DISTINCT user_id) AS c FROM public.notification_preferences', false, true, '')))[1]::text::bigint
       END AS users_with_notification_preferences;

\echo '=== S13. Cross-table references (counts only): staff without user, tasks without creator, support rows without staff or task'
SELECT CASE WHEN to_regclass('public.staff') IS NULL OR to_regclass('public.users') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.staff s WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = s.user_id)', false, true, '')))[1]::text::bigint
       END AS staff_without_user,
       CASE WHEN to_regclass('public.tasks') IS NULL OR to_regclass('public.users') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.tasks t WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = t.created_by_id)', false, true, '')))[1]::text::bigint
       END AS tasks_without_creator,
       CASE WHEN to_regclass('public.support_assignments') IS NULL OR to_regclass('public.staff') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.support_assignments x WHERE NOT EXISTS (SELECT 1 FROM public.staff s WHERE s.id = x.staff_id)', false, true, '')))[1]::text::bigint
       END AS support_rows_without_staff,
       CASE WHEN to_regclass('public.support_assignments') IS NULL OR to_regclass('public.tasks') IS NULL THEN NULL
            ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM public.support_assignments x WHERE NOT EXISTS (SELECT 1 FROM public.tasks t WHERE t.id = x.task_id)', false, true, '')))[1]::text::bigint
       END AS support_rows_without_task;

\echo '=== S14. Read-only proof (still read-only at end of session)'
SELECT current_setting('transaction_read_only') AS transaction_read_only_at_end;
