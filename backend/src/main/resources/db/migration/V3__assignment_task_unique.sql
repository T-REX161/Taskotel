-- V3: database-level guarantee of one assignment row per task (constraint uk_assignments_task_id).
--
-- Additive only: no rows, foreign keys, other indexes or columns are changed.
-- Every assumption about the existing schema is checked first. Any unexpected state raises an
-- exception and, because Flyway runs this migration in a single PostgreSQL transaction, nothing
-- from this file is kept.
--
-- Matches the model enforced by AssignmentServiceImpl (existsByTaskId) and relied on by the task
-- status rules: a task has at most one assignment row, and reassignment updates that row in place.

-- Fail fast instead of waiting if another session holds a conflicting lock on the tables.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    v_schema           text := current_schema();
    v_namespace        regnamespace;
    v_assignments      regclass;
    v_tasks            regclass;
    v_relkind          "char";
    v_task_attnum      int2;
    v_tasks_id_attnum  int2;
    v_col              record;
    v_con              record;
    v_duplicates       bigint;
BEGIN
    IF v_schema IS NULL THEN
        RAISE EXCEPTION 'V3: no current schema is set. No changes were made.';
    END IF;
    v_namespace   := to_regnamespace(v_schema);
    v_assignments := to_regclass(format('%I.assignments', v_schema));
    v_tasks       := to_regclass(format('%I.tasks', v_schema));

    -- 1. Both tables exist, and assignments is an ordinary table
    IF v_assignments IS NULL OR v_tasks IS NULL THEN
        RAISE EXCEPTION 'V3: %.assignments or %.tasks not found. No changes were made.', v_schema, v_schema;
    END IF;
    SELECT relkind INTO v_relkind FROM pg_class WHERE oid = v_assignments;
    IF v_relkind <> 'r' THEN
        RAISE EXCEPTION 'V3: %.assignments is not an ordinary table (relkind %). No changes were made.', v_schema, v_relkind;
    END IF;

    -- 2. assignments.task_id exists and is uuid NOT NULL
    SELECT data_type, is_nullable INTO v_col
      FROM information_schema.columns
     WHERE table_schema = v_schema AND table_name = 'assignments' AND column_name = 'task_id';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V3: column %.assignments.task_id not found. No changes were made.', v_schema;
    END IF;
    IF v_col.data_type <> 'uuid' OR v_col.is_nullable <> 'NO' THEN
        RAISE EXCEPTION 'V3: %.assignments.task_id must be uuid NOT NULL but is % (nullable: %). No changes were made.',
            v_schema, v_col.data_type, v_col.is_nullable;
    END IF;

    SELECT attnum INTO v_task_attnum
      FROM pg_attribute WHERE attrelid = v_assignments AND attname = 'task_id' AND NOT attisdropped;
    SELECT attnum INTO v_tasks_id_attnum
      FROM pg_attribute WHERE attrelid = v_tasks AND attname = 'id' AND NOT attisdropped;
    IF v_tasks_id_attnum IS NULL THEN
        RAISE EXCEPTION 'V3: column %.tasks.id not found. No changes were made.', v_schema;
    END IF;

    -- 3. task_id is a validated foreign key to tasks(id). Matched by columns, not by name:
    --    existing databases use a Hibernate-generated name, fresh ones the PostgreSQL default.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = v_assignments AND contype = 'f'
           AND conkey = ARRAY[v_task_attnum] AND confrelid = v_tasks AND confkey = ARRAY[v_tasks_id_attnum]
           AND convalidated
    ) THEN
        RAISE EXCEPTION 'V3: expected validated foreign key %.assignments(task_id) -> tasks(id) not found. No changes were made.', v_schema;
    END IF;

    -- 4. No task has more than one assignment row
    EXECUTE format(
        'SELECT count(*) FROM (SELECT task_id FROM %I.assignments GROUP BY task_id HAVING count(*) > 1) d',
        v_schema) INTO v_duplicates;
    IF v_duplicates > 0 THEN
        RAISE EXCEPTION 'V3: % task(s) have more than one assignment row. Resolve the duplicates, then restart. No changes were made.',
            v_duplicates;
    END IF;

    -- 5. Constraint-name collisions (any constraint kind; PostgreSQL 18 also lists NOT NULL rules here)
    SELECT conrelid, contype, pg_get_constraintdef(oid) AS def INTO v_con
      FROM pg_constraint
     WHERE connamespace = v_namespace AND conname = 'uk_assignments_task_id';
    IF FOUND THEN
        IF v_con.conrelid = v_assignments AND v_con.contype = 'u' AND v_con.def = 'UNIQUE (task_id)' THEN
            RAISE NOTICE 'V3: uk_assignments_task_id already exists with the expected definition; nothing to do.';
            RETURN;  -- not labelled as created by V3, so the rollback scripts will leave it alone
        END IF;
        RAISE EXCEPTION 'V3: a constraint named uk_assignments_task_id already exists with an unexpected definition (% on %). No changes were made.',
            v_con.def, v_con.conrelid::regclass;
    END IF;
    -- Any other relation with that name (for example an index) would block the constraint's own index
    IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = v_namespace AND relname = 'uk_assignments_task_id') THEN
        RAISE EXCEPTION 'V3: a relation named uk_assignments_task_id already exists in schema %. No changes were made.', v_schema;
    END IF;

    -- 6. A unique index on task_id under another name needs manual review, not a second copy
    IF EXISTS (
        SELECT 1 FROM pg_index
         WHERE indrelid = v_assignments AND indisunique
           AND indnkeyatts = 1 AND indkey[0] = v_task_attnum AND indpred IS NULL
    ) THEN
        RAISE EXCEPTION 'V3: %.assignments(task_id) already has a unique index under another name; review manually. No changes were made.', v_schema;
    END IF;

    -- 7. The only change: add the constraint and label it so rollback scripts can prove V3 created it
    EXECUTE format('ALTER TABLE %I.assignments ADD CONSTRAINT uk_assignments_task_id UNIQUE (task_id)', v_schema);
    EXECUTE format('COMMENT ON CONSTRAINT uk_assignments_task_id ON %I.assignments IS %L',
                   v_schema, 'Created by Flyway V3: one assignment row per task');
    RAISE NOTICE 'V3: added constraint uk_assignments_task_id on %.assignments(task_id).', v_schema;
END
$$;
