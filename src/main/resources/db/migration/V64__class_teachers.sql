-- Academics -> Assign Class Teacher: the teachers in charge of a section (one or more).

CREATE TABLE class_teachers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
    staff_profile_id UUID NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT class_teachers_section_staff_key UNIQUE (section_id, staff_profile_id)
);

CREATE INDEX class_teachers_staff_idx ON class_teachers(staff_profile_id);
