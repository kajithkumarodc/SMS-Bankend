-- One school, standard classes LKG to Class 12.
--
-- 1. The app serves exactly one school: AKA Higher Secondary School. Any existing schools are
--    consolidated into the oldest one (renamed), their records re-pointed to it, and the others
--    removed -- nothing that belongs to a school is deleted. A unique index then allows only one
--    schools row from here on.
-- 2. Classes get a display order, and the standard LKG, UKG, Class 1 ... Class 12 exist in that
--    order. An existing "Grade N" class (1-12) is renamed "Class N" so its sections and students
--    carry over instead of a duplicate being created.

-- --- 1. Single school -------------------------------------------------------------------

DO $$
DECLARE
    keep_id UUID;
    clashes TEXT;
BEGIN
    SELECT id INTO keep_id FROM schools ORDER BY created_at, id LIMIT 1;

    IF keep_id IS NULL THEN
        INSERT INTO schools (id, name) VALUES (gen_random_uuid(), 'AKA Higher Secondary School');
        RETURN;
    END IF;

    -- Classes and subjects are unique per school by name; merging two schools that both have,
    -- say, "Class 5" needs a person to decide how. Stop rather than guess.
    SELECT string_agg(DISTINCT c.name, ', ') INTO clashes
    FROM classes c JOIN classes k ON k.school_id = keep_id AND k.name = c.name
    WHERE c.school_id <> keep_id;
    IF clashes IS NULL THEN
        SELECT string_agg(DISTINCT s.name, ', ') INTO clashes
        FROM subjects s JOIN subjects k ON k.school_id = keep_id AND k.name = s.name
        WHERE s.school_id <> keep_id;
    END IF;
    IF clashes IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot merge schools automatically: more than one school has % -- merge them by hand first', clashes;
    END IF;

    UPDATE students SET school_id = keep_id WHERE school_id <> keep_id;
    UPDATE classes SET school_id = keep_id WHERE school_id <> keep_id;
    UPDATE subjects SET school_id = keep_id WHERE school_id <> keep_id;
    UPDATE fee_structures SET school_id = keep_id WHERE school_id <> keep_id;
    UPDATE admission_cycles SET school_id = keep_id WHERE school_id <> keep_id;
    UPDATE admission_applications SET school_id = keep_id WHERE school_id <> keep_id;
    DELETE FROM schools WHERE id <> keep_id;

    UPDATE schools SET name = 'AKA Higher Secondary School' WHERE id = keep_id;
END $$;

-- Exactly one row, ever: every row would have the same index key (true).
CREATE UNIQUE INDEX schools_single_school_idx ON schools ((true));

-- --- 2. Standard classes ------------------------------------------------------------------

-- Lower comes first. Standard classes get 0-13; anything else is listed after them.
ALTER TABLE classes ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 1000;

UPDATE classes c SET name = 'Class ' || substring(c.name FROM '^Grade\s+(\d+)$')
WHERE c.name ~ '^Grade\s+(\d+)$'
  AND substring(c.name FROM '^Grade\s+(\d+)$')::int BETWEEN 1 AND 12
  AND NOT EXISTS (SELECT 1 FROM classes o WHERE o.school_id = c.school_id
                  AND o.name = 'Class ' || substring(c.name FROM '^Grade\s+(\d+)$'));

WITH standard(name, sort_order) AS (
    VALUES ('LKG', 0), ('UKG', 1),
           ('Class 1', 2), ('Class 2', 3), ('Class 3', 4), ('Class 4', 5), ('Class 5', 6), ('Class 6', 7),
           ('Class 7', 8), ('Class 8', 9), ('Class 9', 10), ('Class 10', 11), ('Class 11', 12), ('Class 12', 13)
)
INSERT INTO classes (id, school_id, name, sort_order)
SELECT gen_random_uuid(), s.id, st.name, st.sort_order
FROM standard st CROSS JOIN schools s
WHERE NOT EXISTS (SELECT 1 FROM classes c WHERE c.school_id = s.id AND c.name = st.name);

UPDATE classes c SET sort_order = st.sort_order
FROM (VALUES ('LKG', 0), ('UKG', 1),
             ('Class 1', 2), ('Class 2', 3), ('Class 3', 4), ('Class 4', 5), ('Class 5', 6), ('Class 6', 7),
             ('Class 7', 8), ('Class 8', 9), ('Class 9', 10), ('Class 10', 11), ('Class 11', 12), ('Class 12', 13))
     AS st(name, sort_order)
WHERE c.name = st.name;
