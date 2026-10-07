-- Academics -> Subjects, Subject Group and Class Timetable.

-- Subjects get a code (shown as "English (210)") and a type.
ALTER TABLE subjects
    ADD COLUMN code VARCHAR(30),
    ADD COLUMN subject_type VARCHAR(20) NOT NULL DEFAULT 'THEORY',
    ADD CONSTRAINT subjects_type_check CHECK (subject_type IN ('THEORY', 'PRACTICAL'));

CREATE UNIQUE INDEX subjects_code_key ON subjects (lower(code)) WHERE code IS NOT NULL;

-- A subject group: the subjects a set of sections of one class study together.
CREATE TABLE subject_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT subject_groups_name_key UNIQUE (name)
);

CREATE TABLE subject_group_sections (
    group_id UUID NOT NULL REFERENCES subject_groups(id) ON DELETE CASCADE,
    section_id UUID NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, section_id)
);

CREATE TABLE subject_group_subjects (
    group_id UUID NOT NULL REFERENCES subject_groups(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES subjects(id),
    PRIMARY KEY (group_id, subject_id)
);

-- One period of a section's week. day_of_week: 1 = Monday ... 7 = Sunday.
CREATE TABLE timetable_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
    subject_group_id UUID NOT NULL REFERENCES subject_groups(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES subjects(id),
    day_of_week SMALLINT NOT NULL,
    time_from TIME NOT NULL,
    time_to TIME NOT NULL,
    staff_profile_id UUID REFERENCES staff_profiles(id) ON DELETE SET NULL,
    room_no VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT timetable_entries_day_check CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT timetable_entries_times_check CHECK (time_to > time_from)
);

CREATE INDEX timetable_entries_section_idx ON timetable_entries(section_id, day_of_week, time_from);
CREATE INDEX timetable_entries_staff_idx ON timetable_entries(staff_profile_id, day_of_week);

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('TIMETABLE_VIEW'), ('TIMETABLE_MANAGE'), ('SUBJECT_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name IN ('TIMETABLE_VIEW', 'TIMETABLE_MANAGE', 'SUBJECT_MANAGE')
ON CONFLICT DO NOTHING;

-- Principals and teachers can read the timetable.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'TIMETABLE_VIEW'
WHERE r.name IN ('PRINCIPAL', 'TEACHER')
ON CONFLICT DO NOTHING;
