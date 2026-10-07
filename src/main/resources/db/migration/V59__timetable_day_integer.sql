-- day_of_week is read as an int by the entity; match the column type (V58 declared SMALLINT).

ALTER TABLE timetable_entries ALTER COLUMN day_of_week TYPE INTEGER;
