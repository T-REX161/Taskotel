-- V21: reconcile staff.department and tasks.department with the department model, whichever way the schema was built.
--
-- Two schemas exist in the wild:
--   (a) built by Flyway through V4: the enum CHECK constraints are already gone and the foreign keys fk_staff_department_code and
--       fk_tasks_department_code (to departments.code) and their indexes already exist, so this file changes nothing there;
--   (b) built by Hibernate (ddl-auto=update, no Flyway history): departments exists, but staff_department_check and tasks_department_check
--       (the six old enum codes) are still there and the foreign keys are missing. The CHECKs reject every department created after the six
--       originals, so a new department can never be given to a Staff member or a task. V4 cannot be run on that schema (departments already exists).
--
-- What it does, in one transaction (all or nothing):
--   1. checks the tables and the unique departments.code exist, and that nothing unexpected is using the names it will create;
--   2. refuses (and changes nothing) when any non-null staff.department or tasks.department has no matching departments.code;
--   3. drops staff_department_check and tasks_department_check if present, but only after proving each is exactly the legacy CHECK: on the
--      department column alone, allowing only the six original codes. A same-named constraint that differs stops the migration;
--   4. adds the foreign keys fk_staff_department_code and fk_tasks_department_code only when an equivalent VALIDATED one is not already there
--      (an equivalent NOT VALID one is validated instead, after the orphan check, never skipped);
--   5. adds idx_staff_department and idx_tasks_department only when no index already leads with the department column.
--
-- No row is updated, deleted or rewritten, and no column is made NOT NULL: NULL stays valid (V19), so an Admin, Manager or Staff account may
-- have no department, and a task may have none. An orphaned department code is never repaired silently: the migration stops and names it.
--
-- Not applied automatically: Flyway is off unless WB_FLYWAY_ENABLED=true. Run by hand in one transaction, after V19 and V20 and after review:
--   psql -1 -v ON_ERROR_STOP=1 -f V21__department_constraints_reconcile.sql
-- Running it again is harmless (everything is checked before it is created).
--
-- Do not run it on a Flyway-managed database that has not run V4; there it fails at the first check (no departments table) and changes nothing.
--
-- ROLLBACK: dropping the foreign keys (ALTER TABLE staff DROP CONSTRAINT fk_staff_department_code; likewise for tasks) is safe at any time.
-- The old CHECK constraints are NOT restored: they would reject every department created after the original six.

SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    v_schema      text := current_schema();
    v_staff       regclass := to_regclass(format('%I.staff', current_schema()));
    v_tasks       regclass := to_regclass(format('%I.tasks', current_schema()));
    v_departments regclass := to_regclass(format('%I.departments', current_schema()));
    v_table       regclass;
    v_name        text;
    v_con         record;
    v_literals    text[];
    v_expected    text[] := ARRAY['HOUSEKEEPING', 'FRONT_DESK', 'MAINTENANCE', 'ROOM_SERVICE', 'FOOD_AND_BEVERAGE', 'SECURITY'];
    v_orphans     text;
    v_dept_att    smallint;
    v_code_att    smallint;
BEGIN
    -- 1. Preconditions
    IF v_staff IS NULL OR v_tasks IS NULL THEN
        RAISE EXCEPTION 'V21: %.staff and %.tasks must exist. No changes were made.', v_schema, v_schema;
    END IF;
    IF v_departments IS NULL THEN
        RAISE EXCEPTION 'V21: %.departments does not exist. No changes were made.', v_schema;
    END IF;
    SELECT attnum INTO v_code_att FROM pg_attribute WHERE attrelid = v_departments AND attname = 'code' AND NOT attisdropped;
    IF v_code_att IS NULL THEN
        RAISE EXCEPTION 'V21: %.departments has no code column. No changes were made.', v_schema;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = v_departments AND contype IN ('u', 'p') AND conkey = ARRAY[v_code_att]) THEN
        RAISE EXCEPTION 'V21: departments.code is not unique, so it cannot be referenced. No changes were made.';
    END IF;
    FOREACH v_table IN ARRAY ARRAY[v_staff, v_tasks] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = v_table AND attname = 'department' AND NOT attisdropped) THEN
            RAISE EXCEPTION 'V21: % has no department column. No changes were made.', v_table;
        END IF;
    END LOOP;

    -- 2. Orphaned department codes stop the whole migration. Nothing is repaired.
    SELECT string_agg(DISTINCT quote_literal(s.department), ', ') INTO v_orphans
      FROM staff s
     WHERE s.department IS NOT NULL AND NOT EXISTS (SELECT 1 FROM departments d WHERE d.code = s.department);
    IF v_orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V21: staff.department holds code(s) that are not in departments.code: %. Fix or add the department by hand, then run V21 again. No changes were made.', v_orphans;
    END IF;
    SELECT string_agg(DISTINCT quote_literal(t.department), ', ') INTO v_orphans
      FROM tasks t
     WHERE t.department IS NOT NULL AND NOT EXISTS (SELECT 1 FROM departments d WHERE d.code = t.department);
    IF v_orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V21: tasks.department holds code(s) that are not in departments.code: %. Fix or add the department by hand, then run V21 again. No changes were made.', v_orphans;
    END IF;

    -- 3. The old enum CHECKs, dropped only when a same-named constraint is exactly the legacy one: a CHECK on the department column alone
    --    ("department = ANY (<the six original codes>)", nothing else). Anything different stops the migration. Absent is fine.
    FOREACH v_table IN ARRAY ARRAY[v_staff, v_tasks] LOOP
        v_name := CASE WHEN v_table = v_staff THEN 'staff_department_check' ELSE 'tasks_department_check' END;
        SELECT attnum INTO v_dept_att FROM pg_attribute WHERE attrelid = v_table AND attname = 'department' AND NOT attisdropped;
        SELECT oid, contype, conkey, pg_get_constraintdef(oid) AS def INTO v_con FROM pg_constraint WHERE conrelid = v_table AND conname = v_name;
        IF FOUND THEN
            v_literals := ARRAY(SELECT m[1] FROM regexp_matches(v_con.def, '''([^'']*)''', 'g') AS m);
            IF v_con.contype <> 'c'
               OR v_con.conkey IS DISTINCT FROM ARRAY[v_dept_att]
               OR v_con.def !~ '^CHECK \(+\(department\)::text = ANY \('
               OR regexp_replace(v_con.def, '''[^'']*''', '', 'g') ~* '\m(and|or|not|null|is)\M'
               OR cardinality(v_literals) <> cardinality(v_expected)
               OR NOT (v_literals @> v_expected AND v_literals <@ v_expected) THEN
                RAISE EXCEPTION 'V21: % on % is not the legacy six-department CHECK on the department column alone (%). Review it by hand. No changes were made.', v_name, v_table, v_con.def;
            END IF;
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', v_table, v_name);
            RAISE NOTICE 'V21: dropped obsolete CHECK constraint % on %.', v_name, v_table;
        END IF;
    END LOOP;

    -- 4. Foreign keys to departments.code. An equivalent VALIDATED one (whatever its name) is enough. An equivalent NOT VALID one is not:
    --    it is validated here (the orphan checks above already proved every row satisfies it). Otherwise the foreign key is added.
    FOREACH v_table IN ARRAY ARRAY[v_staff, v_tasks] LOOP
        v_name := CASE WHEN v_table = v_staff THEN 'fk_staff_department_code' ELSE 'fk_tasks_department_code' END;
        SELECT attnum INTO v_dept_att FROM pg_attribute WHERE attrelid = v_table AND attname = 'department' AND NOT attisdropped;
        IF EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = v_table AND contype = 'f' AND confrelid = v_departments
                      AND conkey = ARRAY[v_dept_att] AND confkey = ARRAY[v_code_att] AND convalidated) THEN
            CONTINUE;
        END IF;
        FOR v_con IN SELECT conname FROM pg_constraint
                      WHERE conrelid = v_table AND contype = 'f' AND confrelid = v_departments
                        AND conkey = ARRAY[v_dept_att] AND confkey = ARRAY[v_code_att] AND NOT convalidated
        LOOP
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', v_table, v_con.conname);
            RAISE NOTICE 'V21: validated existing NOT VALID foreign key % on %.', v_con.conname, v_table;
        END LOOP;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conrelid = v_table AND contype = 'f' AND confrelid = v_departments
                          AND conkey = ARRAY[v_dept_att] AND confkey = ARRAY[v_code_att] AND convalidated) THEN
            IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = v_table AND conname = v_name) THEN
                RAISE EXCEPTION 'V21: a constraint named % already exists on % but is not the department foreign key. Review it by hand. No changes were made.', v_name, v_table;
            END IF;
            EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (department) REFERENCES %s (code)', v_table, v_name, v_departments);
            RAISE NOTICE 'V21: added foreign key % on %.', v_name, v_table;
        END IF;
    END LOOP;

    -- 5. Indexes on the referencing columns, unless one already leads with the department column (whatever its name)
    FOREACH v_table IN ARRAY ARRAY[v_staff, v_tasks] LOOP
        v_name := CASE WHEN v_table = v_staff THEN 'idx_staff_department' ELSE 'idx_tasks_department' END;
        SELECT attnum INTO v_dept_att FROM pg_attribute WHERE attrelid = v_table AND attname = 'department' AND NOT attisdropped;
        IF NOT EXISTS (SELECT 1 FROM pg_index WHERE indrelid = v_table AND indkey[0] = v_dept_att) THEN
            IF EXISTS (SELECT 1 FROM pg_class WHERE relname = v_name AND relnamespace = (SELECT relnamespace FROM pg_class WHERE oid = v_table)) THEN
                RAISE EXCEPTION 'V21: a relation named % already exists but is not an index on %.department. Review it by hand. No changes were made.', v_name, v_table;
            END IF;
            EXECUTE format('CREATE INDEX %I ON %s (department)', v_name, v_table);
            RAISE NOTICE 'V21: created index % on %.', v_name, v_table;
        END IF;
    END LOOP;
END
$$;
