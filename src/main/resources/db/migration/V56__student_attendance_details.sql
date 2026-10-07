-- Attendance -> Student Attendance: Holiday and Half Day marks, plus the entry/exit time
-- and note of a mark (same shape as staff_attendance, V48).
--
-- `source` and its CHECK are deliberately not added here. V30 (face attendance
-- foundation) already adds the column, and its constraint allows 'FACE_ASSISTED'
-- alongside 'MANUAL'. This migration originally carried both the column and a
-- narrower CHECK (source IN ('MANUAL')), which on a merged schema fails outright
-- ("column source already exists") and, once past that, would forbid the
-- face-assisted marks the face programme writes. V30's column and constraint stand.

ALTER TABLE attendance_records
    ADD COLUMN entry_time TIME,
    ADD COLUMN exit_time TIME,
    ADD COLUMN note VARCHAR(500);

ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_status_check;
ALTER TABLE attendance_records
    ADD CONSTRAINT attendance_records_status_check CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'HOLIDAY', 'HALF_DAY')),
    ADD CONSTRAINT attendance_records_times_check CHECK (entry_time IS NULL OR exit_time IS NULL OR exit_time > entry_time);
