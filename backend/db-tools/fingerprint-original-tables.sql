-- Fingerprint of the four ORIGINAL application tables only (assignments, staff, tasks, users): one line per
-- table with the row count and an MD5 of all rows in id order. READ-ONLY. Prints no column values, so no
-- personal data, emails or password hashes.
--
-- This is the narrow fingerprint the opt-in migration tests (PostgresMigrationIT, DepartmentMigrationIT) use
-- on rehearsal databases that start before V4/V5/V12 exist, to prove a migration left these four tables'
-- rows unchanged. It must keep working on a database that has only these four tables. The backup and restore
-- tooling uses the full seven-table fingerprint.sql instead.
SELECT string_agg(line, E'\n' ORDER BY line) FROM (
    SELECT 'assignments ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) AS line FROM public.assignments t
    UNION ALL
    SELECT 'staff ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.staff t
    UNION ALL
    SELECT 'tasks ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.tasks t
    UNION ALL
    SELECT 'users ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.users t
) x;
