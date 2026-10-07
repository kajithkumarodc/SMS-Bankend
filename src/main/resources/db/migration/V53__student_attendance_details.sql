-- Attendance -> Student Attendance: Holiday and Half Day marks, plus the source, entry/exit time and note of a mark
-- (same shape as staff_attendance, V45).

ALTER TABLE attendance_records
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN entry_time TIME,
    ADD COLUMN exit_time TIME,
    ADD COLUMN note VARCHAR(500);

ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_status_check;
ALTER TABLE attendance_records
    ADD CONSTRAINT attendance_records_status_check CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'HOLIDAY', 'HALF_DAY')),
    ADD CONSTRAINT attendance_records_source_check CHECK (source IN ('MANUAL')),
    ADD CONSTRAINT attendance_records_times_check CHECK (entry_time IS NULL OR exit_time IS NULL OR exit_time > entry_time);
