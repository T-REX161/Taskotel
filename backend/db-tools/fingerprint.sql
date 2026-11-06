-- Application-data fingerprint: one line per application table, in this fixed order (assignments, departments,
-- notification_preferences, staff, support_assignments, tasks, users), each with the row count and an MD5 of
-- all rows in id order. READ-ONLY. Prints no column values, so no personal data, emails or password hashes.
--
-- Used by backup-and-verify.ps1 / restore-to-new-db.ps1 / create-rehearsal-dbs.ps1, which additionally check
-- (DbToolsCommon.ps1) that public contains every one of these tables and no table this file does not cover,
-- and fingerprint flyway_schema_history when present. The result is nine lines in total: these seven, the
-- table list, and the Flyway history line.
--
-- The opt-in migration tests use fingerprint-original-tables.sql (the four tables that exist before V4) instead.
SELECT string_agg(line, E'\n' ORDER BY ord) FROM (
    SELECT 1 AS ord, 'assignments ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) AS line FROM public.assignments t
    UNION ALL
    SELECT 2, 'departments ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.departments t
    UNION ALL
    SELECT 3, 'notification_preferences ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.notification_preferences t
    UNION ALL
    SELECT 4, 'staff ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.staff t
    UNION ALL
    SELECT 5, 'support_assignments ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.support_assignments t
    UNION ALL
    SELECT 6, 'tasks ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.tasks t
    UNION ALL
    SELECT 7, 'users ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), '')) FROM public.users t
) x;
