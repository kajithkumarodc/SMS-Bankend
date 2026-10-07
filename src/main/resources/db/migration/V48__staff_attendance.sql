-- Human Resource -> Staff Attendance: one attendance mark per staff member per day.

CREATE TABLE staff_attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_profile_id UUID NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    attendance_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    -- Where the mark came from; only MANUAL for now (QR / biometric devices can add their own later).
    source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    entry_time TIME,
    exit_time TIME,
    note VARCHAR(500),
    marked_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT staff_attendance_status_check CHECK (
        status IN ('PRESENT', 'LATE', 'ABSENT', 'HALF_DAY', 'HOLIDAY', 'HALF_DAY_SECOND_HALF')),
    CONSTRAINT staff_attendance_source_check CHECK (source IN ('MANUAL')),
    CONSTRAINT staff_attendance_times_check CHECK (entry_time IS NULL OR exit_time IS NULL OR exit_time > entry_time),
    CONSTRAINT staff_attendance_staff_date_key UNIQUE (staff_profile_id, attendance_date)
);

CREATE INDEX staff_attendance_date_idx ON staff_attendance(attendance_date);

-- Its own permissions: the existing ATTENDANCE_* ones belong to student attendance, which teachers hold.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('STAFF_ATTENDANCE_VIEW'), ('STAFF_ATTENDANCE_EDIT')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name IN ('STAFF_ATTENDANCE_VIEW', 'STAFF_ATTENDANCE_EDIT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'STAFF_ATTENDANCE_VIEW'
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
