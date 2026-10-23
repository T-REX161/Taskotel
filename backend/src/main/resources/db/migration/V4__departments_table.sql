-- V4: departments become database records instead of a fixed Java enum.
--
-- What it does, in one transaction (Flyway runs a PostgreSQL migration in a single transaction, so
-- if any statement fails nothing from this file is kept):
--   1. checks every assumption about the existing schema and data, and raises before changing anything;
--   2. creates the departments table (UUID id, unique code, unique case-insensitive name, description,
--      icon_key, color_key, active, optional head_staff_id, timestamps);
--   3. seeds the six departments that existed as an enum, with the exact codes already stored in
--      staff.department and tasks.department;
--   4. drops the two CHECK constraints that Hibernate generated from the old enum
--      (they only allow the six codes, so they would reject every new department);
--   5. adds foreign keys staff.department -> departments.code and tasks.department -> departments.code,
--      plus indexes on those columns.
--
-- No staff, task, assignment or user row is read for change, updated or deleted: staff.department and
-- tasks.department keep their existing values, which now point at the matching department rows.
--
-- Rollback: db-tools/rollback/V4_existing.sql (baselined installations) or V4_fresh.sql.

-- Fail fast instead of waiting if another session holds a conflicting lock on the tables.
SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. Preconditions. Any unexpected state raises and nothing is changed.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_schema    text := current_schema();
    v_namespace regnamespace;
    v_staff     regclass;
    v_tasks     regclass;
    v_col       record;
    v_unknown   bigint;
    v_con       record;
    v_codes     text[] := ARRAY['HOUSEKEEPING', 'FRONT_DESK', 'MAINTENANCE', 'ROOM_SERVICE', 'FOOD_AND_BEVERAGE', 'SECURITY'];
    v_name      text;
BEGIN
    IF v_schema IS NULL THEN
        RAISE EXCEPTION 'V4: no current schema is set. No changes were made.';
    END IF;
    v_namespace := to_regnamespace(v_schema);
    v_staff     := to_regclass(format('%I.staff', v_schema));
    v_tasks     := to_regclass(format('%I.tasks', v_schema));

    -- 1a. staff and tasks exist
    IF v_staff IS NULL OR v_tasks IS NULL THEN
        RAISE EXCEPTION 'V4: %.staff or %.tasks not found. No changes were made.', v_schema, v_schema;
    END IF;

    -- 1b. the department columns exist and are character varying
    FOR v_col IN
        SELECT table_name, data_type
          FROM information_schema.columns
         WHERE table_schema = v_schema AND table_name IN ('staff', 'tasks') AND column_name = 'department'
    LOOP
        IF v_col.data_type <> 'character varying' THEN
            RAISE EXCEPTION 'V4: %.%.department must be character varying but is %. No changes were made.',
                v_schema, v_col.table_name, v_col.data_type;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM information_schema.columns
         WHERE table_schema = v_schema AND table_name IN ('staff', 'tasks') AND column_name = 'department') <> 2 THEN
        RAISE EXCEPTION 'V4: expected a department column on both %.staff and %.tasks. No changes were made.', v_schema, v_schema;
    END IF;

    -- 1c. nothing already uses the names V4 is about to create
    IF to_regclass(format('%I.departments', v_schema)) IS NOT NULL THEN
        RAISE EXCEPTION 'V4: %.departments already exists. If a development run created it, review it by hand first. No changes were made.', v_schema;
    END IF;
    FOREACH v_name IN ARRAY ARRAY['uk_departments_code', 'uk_departments_name_lower', 'fk_departments_head_staff',
                                  'fk_staff_department_code', 'fk_tasks_department_code',
                                  'idx_staff_department', 'idx_tasks_department']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = v_namespace AND relname = v_name)
           OR EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = v_namespace AND conname = v_name) THEN
            RAISE EXCEPTION 'V4: a relation or constraint named % already exists in schema %. No changes were made.', v_name, v_schema;
        END IF;
    END LOOP;

    -- 1d. every stored department code is one of the six that will be seeded (so the foreign keys will validate)
    EXECUTE format('SELECT count(*) FROM %I.staff WHERE department IS NOT NULL AND department <> ALL ($1)', v_schema)
        INTO v_unknown USING v_codes;
    IF v_unknown > 0 THEN
        RAISE EXCEPTION 'V4: % staff row(s) have a department code outside the six built-in departments. No changes were made.', v_unknown;
    END IF;
    EXECUTE format('SELECT count(*) FROM %I.tasks WHERE department IS NOT NULL AND department <> ALL ($1)', v_schema)
        INTO v_unknown USING v_codes;
    IF v_unknown > 0 THEN
        RAISE EXCEPTION 'V4: % task row(s) have a department code outside the six built-in departments. No changes were made.', v_unknown;
    END IF;

    -- 1e. any CHECK constraint that mentions department must be the Hibernate enum check (exactly the six codes);
    --     anything else needs a human decision, not a silent drop
    FOR v_con IN
        SELECT conrelid::regclass AS tbl, conname, pg_get_constraintdef(oid) AS def
          FROM pg_constraint
         WHERE contype = 'c' AND conrelid IN (v_staff, v_tasks)
           AND pg_get_constraintdef(oid) ~* '\mdepartment\M'
    LOOP
        IF (SELECT count(*) FROM regexp_matches(v_con.def, '''[A-Z_]+''', 'g')) <> 6
           OR EXISTS (SELECT 1 FROM unnest(v_codes) c WHERE position('''' || c || '''' IN v_con.def) = 0) THEN
            RAISE EXCEPTION 'V4: CHECK constraint % on % mentions department but is not the six-value enum check (%). Review manually. No changes were made.',
                v_con.conname, v_con.tbl, v_con.def;
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. The departments table
-- ---------------------------------------------------------------------------------------------
CREATE TABLE departments (
    id            UUID         PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    icon_key      VARCHAR(50)  NOT NULL DEFAULT 'building',
    color_key     VARCHAR(50)  NOT NULL DEFAULT 'gray',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    head_staff_id UUID,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP,
    CONSTRAINT uk_departments_code UNIQUE (code),
    CONSTRAINT fk_departments_head_staff FOREIGN KEY (head_staff_id) REFERENCES staff (id) ON DELETE SET NULL
);

-- One name, whatever its letter case
CREATE UNIQUE INDEX uk_departments_name_lower ON departments (lower(name));

-- ---------------------------------------------------------------------------------------------
-- 3. The six existing departments, in their original order (clock_timestamp() advances between statements,
--    so listing by creation time keeps that order). Codes match the values already stored in staff and tasks.
-- ---------------------------------------------------------------------------------------------
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'HOUSEKEEPING', 'Housekeeping', NULL, 'sprout', 'green', TRUE, clock_timestamp());
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'FRONT_DESK', 'Front Desk', NULL, 'bell', 'blue', TRUE, clock_timestamp());
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'MAINTENANCE', 'Maintenance', NULL, 'wrench', 'purple', TRUE, clock_timestamp());
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'ROOM_SERVICE', 'Room Service', NULL, 'coffee', 'amber', TRUE, clock_timestamp());
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'FOOD_AND_BEVERAGE', 'Food & Beverage', NULL, 'utensils', 'orange', TRUE, clock_timestamp());
INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) VALUES (gen_random_uuid(), 'SECURITY', 'Security', NULL, 'shield', 'cyan', TRUE, clock_timestamp());

-- ---------------------------------------------------------------------------------------------
-- 4. Drop the enum CHECK constraints that Hibernate generated (verified by the checks above)
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_con record;
BEGIN
    FOR v_con IN
        SELECT conrelid::regclass AS tbl, conname
          FROM pg_constraint
         WHERE contype = 'c'
           AND conrelid IN (to_regclass(format('%I.staff', current_schema())), to_regclass(format('%I.tasks', current_schema())))
           AND pg_get_constraintdef(oid) ~* '\mdepartment\M'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', v_con.tbl, v_con.conname);
        RAISE NOTICE 'V4: dropped enum CHECK constraint % on %.', v_con.conname, v_con.tbl;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. Staff and tasks now reference departments by code (existing values are unchanged and now validated)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE staff ADD CONSTRAINT fk_staff_department_code FOREIGN KEY (department) REFERENCES departments (code);
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_department_code FOREIGN KEY (department) REFERENCES departments (code);
CREATE INDEX idx_staff_department ON staff (department);
CREATE INDEX idx_tasks_department ON tasks (department);

-- Label the table so the rollback scripts can prove V4 created it
COMMENT ON TABLE departments IS 'Created by Flyway V4: departments table (replaces the department enum)';
