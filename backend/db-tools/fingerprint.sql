-- Application-data fingerprint: one line per application table (assignments, staff, tasks, users,
-- which are all the tables in public before Flyway) with the row count and an MD5 of all rows in
-- id order. READ-ONLY. Prints no column values, so no personal data, emails or password hashes.
--
-- Used by PostgresMigrationIT to prove that a migration left application rows unchanged, and by
-- backup-and-verify.ps1 / restore-to-new-db.ps1, which additionally check (DbToolsCommon.ps1) that
-- public contains no table this file does not cover and fingerprint flyway_schema_history when present.
SELECT string_agg(line, E'\n' ORDER BY line) FROM (
    SELECT 'assignments ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) AS line FROM public.assignments t
    UNION ALL
    SELECT 'staff ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.staff t
    UNION ALL
    SELECT 'tasks ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.tasks t
    UNION ALL
    SELECT 'users ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.users t
) x;
