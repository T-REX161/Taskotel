-- P4a preflight report: READ-ONLY schema inspection. Prints catalog metadata, counts and booleans only;
-- never record contents, emails, password hashes or credentials.
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
-- Section S0 must show transaction_read_only = on, and S11 must still show on at the end.
-- Requirements for V3: S7 uk_name_uses = 0, S8 task_id uuid / not null / one validated FK to tasks(id) /
-- 0 unique indexes on task_id, S9 tasks_with_duplicate_assignments = 0.

-- P4a-0 preflight: READ-ONLY. Catalog metadata, counts and booleans only; no record contents.
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

\echo '=== S2. Tables in public with owner and row counts (counts only)'
SELECT c.relname AS table, pg_get_userbyid(c.relowner) AS owner,
       CASE c.relname WHEN 'users' THEN (SELECT count(*) FROM public.users)
                      WHEN 'staff' THEN (SELECT count(*) FROM public.staff)
                      WHEN 'tasks' THEN (SELECT count(*) FROM public.tasks)
                      WHEN 'assignments' THEN (SELECT count(*) FROM public.assignments) END AS row_count
FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r','p') ORDER BY 1;

\echo '=== S3. Flyway history table anywhere?'
SELECT n.nspname AS schema, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname ILIKE '%schema_history%' OR c.relname ILIKE 'flyway%';

\echo '=== S4. Columns: type, length, nullability, default'
SELECT table_name AS table, ordinal_position AS pos, column_name AS column,
       CASE WHEN character_maximum_length IS NOT NULL THEN data_type || '(' || character_maximum_length || ')'
            WHEN datetime_precision IS NOT NULL AND data_type LIKE 'time%' THEN data_type || ' p' || datetime_precision
            ELSE data_type END AS type,
       is_nullable AS nullable, coalesce(column_default, '') AS "default"
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name IN ('users','staff','tasks','assignments')
ORDER BY table_name, ordinal_position;

\echo '=== S5. Constraints: PK / UNIQUE / FK (with ON DELETE / ON UPDATE) / CHECK (names only for CHECK)'
SELECT conrelid::regclass AS table, conname AS name,
       CASE contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' WHEN 'f' THEN 'FOREIGN KEY' WHEN 'c' THEN 'CHECK' ELSE contype::text END AS kind,
       CASE WHEN contype = 'c' THEN '(check on ' || (SELECT string_agg(attname, ',') FROM pg_attribute WHERE attrelid = conrelid AND attnum = ANY(conkey)) || ')'
            ELSE pg_get_constraintdef(oid) END AS definition,
       CASE WHEN contype = 'f' THEN CASE confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END END AS on_delete,
       CASE WHEN contype = 'f' THEN CASE confupdtype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END END AS on_update,
       convalidated AS validated, condeferrable AS deferrable
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY conrelid::regclass::text, contype, conname;

\echo '=== S6. Indexes (all, public schema)'
SELECT tablename AS table, indexname AS index, indexdef AS definition
FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename, indexname;

\echo '=== S7. Name collision: anything called uk_assignments_task_id (any schema, any object kind)'
SELECT 'pg_constraint' AS catalog, connamespace::regnamespace::text AS schema, conname AS name, contype::text AS kind
FROM pg_constraint WHERE conname = 'uk_assignments_task_id'
UNION ALL
SELECT 'pg_class', relnamespace::regnamespace::text, relname, relkind::text FROM pg_class WHERE relname = 'uk_assignments_task_id';
SELECT (SELECT count(*) FROM pg_constraint WHERE conname = 'uk_assignments_task_id')
     + (SELECT count(*) FROM pg_class WHERE relname = 'uk_assignments_task_id') AS uk_name_uses;

\echo '=== S8. assignments.task_id: type, NOT NULL, FK target, existing unique index on task_id'
WITH a AS (SELECT attnum, format_type(atttypid, atttypmod) AS type, attnotnull FROM pg_attribute
           WHERE attrelid = 'public.assignments'::regclass AND attname = 'task_id' AND NOT attisdropped)
SELECT (SELECT type FROM a) AS task_id_type,
       (SELECT attnotnull FROM a) AS task_id_not_null,
       (SELECT string_agg(conname || ' -> ' || confrelid::regclass || '(' ||
               (SELECT string_agg(attname, ',') FROM pg_attribute WHERE attrelid = confrelid AND attnum = ANY(confkey)) || ')'
               || ' validated=' || convalidated, '; ')
          FROM pg_constraint WHERE conrelid = 'public.assignments'::regclass AND contype = 'f'
           AND conkey = ARRAY[(SELECT attnum FROM a)]) AS task_id_foreign_keys,
       (SELECT count(*) FROM pg_index WHERE indrelid = 'public.assignments'::regclass AND indisunique
           AND indnkeyatts = 1 AND indkey[0] = (SELECT attnum FROM a) AND indpred IS NULL) AS unique_indexes_on_task_id,
       (SELECT count(*) FROM pg_index WHERE indrelid = 'public.assignments'::regclass AND indisunique
           AND (SELECT attnum FROM a) = ANY(indkey::int2[])) AS unique_indexes_including_task_id;

\echo '=== S9. Duplicate / integrity counts on assignments (counts only)'
SELECT (SELECT count(*) FROM public.assignments) AS assignments,
       (SELECT count(DISTINCT task_id) FROM public.assignments) AS distinct_task_ids,
       (SELECT count(*) FROM (SELECT task_id FROM public.assignments GROUP BY task_id HAVING count(*) > 1) d) AS tasks_with_duplicate_assignments,
       (SELECT count(*) FROM public.assignments WHERE task_id IS NULL) AS null_task_ids,
       (SELECT count(*) FROM public.assignments a WHERE NOT EXISTS (SELECT 1 FROM public.tasks t WHERE t.id = a.task_id)) AS orphan_task_refs,
       (SELECT count(*) FROM public.assignments a WHERE NOT EXISTS (SELECT 1 FROM public.staff s WHERE s.id = a.staff_id)) AS orphan_staff_refs;

\echo '=== S10. V1 objects missing / V2 column state'
SELECT i.name AS v1_index, EXISTS (SELECT 1 FROM pg_class WHERE relname = i.name AND relnamespace = 'public'::regnamespace) AS present
FROM (VALUES ('idx_users_username'),('idx_users_email'),('idx_staff_user_id'),('idx_staff_email'),
             ('idx_tasks_created_by'),('idx_tasks_status'),('idx_assignments_task_id'),('idx_assignments_staff_id')) i(name);
SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'credentials_setup_required';
SELECT count(*) FILTER (WHERE credentials_setup_required IS NULL) AS null_credentials_setup_required FROM public.users;

\echo '=== S11. Read-only proof (still read-only at end of session)'
SELECT current_setting('transaction_read_only') AS transaction_read_only_at_end;
