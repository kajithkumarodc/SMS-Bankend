-- A class does not need sections. A class with no real sections (e.g. LKG with one group of children)
-- holds its students in one hidden "default" section, so everything that works per section (admission,
-- attendance, exams, promotion, fees) works for the whole class without a separate code path.
--
-- Lifecycle, enforced in ClassService:
--   * every new class gets a default section;
--   * adding the first real section converts the default one in place (rename + flag off), so students
--     already in the class and their academic history simply end up in that section;
--   * deleting the last real section (only possible when empty) turns it back into the default one.
-- The UI never shows the default section's name; the label below is only a readable fallback.

ALTER TABLE sections ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;

-- At most one default section per class.
CREATE UNIQUE INDEX sections_one_default_per_class_idx ON sections (class_id) WHERE is_default;

INSERT INTO sections (class_id, name, is_default)
SELECT c.id, 'No section', true
FROM classes c
WHERE NOT EXISTS (SELECT 1 FROM sections s WHERE s.class_id = c.id);
