-- Homework / assignments (the reference product's "Download Center + Homework"
-- module). A teacher posts a piece of homework to one section of a class for one
-- subject; every student in that section sees it, and their individual state
-- lives in homework_submissions.
--
-- Two tables rather than one because "what was set" and "what each student did
-- about it" have different lifecycles: editing the due date must not touch 40
-- submission rows, and a submission row is created lazily the first time a
-- student's state stops being the default.
--
-- No tenant_id: tenancy was removed in V18__remove_multi_tenancy.sql. Grants for
-- the restricted app_user role come from the ALTER DEFAULT PRIVILEGES set up in
-- V4__restricted_app_runtime_role.sql.

CREATE TABLE homework (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id UUID NOT NULL REFERENCES classes(id),
    -- Section is required: homework is always set for a specific section's
    -- roster, which is also what makes "who has submitted" answerable.
    section_id UUID NOT NULL REFERENCES sections(id),
    subject_id UUID NOT NULL REFERENCES subjects(id),
    title VARCHAR(200) NOT NULL,
    description TEXT,
    assigned_date DATE NOT NULL,
    due_date DATE NOT NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    -- A due date before the assigned date is always a data-entry error; the
    -- service rejects it too, but the constraint is the source of truth.
    CONSTRAINT homework_due_after_assigned_check CHECK (due_date >= assigned_date)
);

-- The dominant read is "this section's homework, most recent first", for both
-- the teacher's list and the student portal.
CREATE INDEX homework_section_id_assigned_date_idx ON homework(section_id, assigned_date DESC);
CREATE INDEX homework_class_id_idx ON homework(class_id);
CREATE INDEX homework_subject_id_idx ON homework(subject_id);
CREATE INDEX homework_due_date_idx ON homework(due_date);

CREATE TABLE homework_submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    homework_id UUID NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    -- PENDING is the implied state of a student with no row at all, so it is
    -- stored only once a teacher has actively set it back to pending.
    status VARCHAR(20) NOT NULL,
    submitted_at TIMESTAMPTZ,
    remarks VARCHAR(1000),
    marked_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    marked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT homework_submissions_status_check
        CHECK (status IN ('PENDING', 'SUBMITTED', 'LATE', 'NOT_SUBMITTED'))
);

-- One row per student per homework: the upsert in HomeworkService relies on this.
CREATE UNIQUE INDEX homework_submissions_homework_student_key
    ON homework_submissions(homework_id, student_id);
CREATE INDEX homework_submissions_student_id_idx ON homework_submissions(student_id);

-- RBAC: homework is a teacher's daily job, so both the teaching roles and the
-- admin roles get it. Matches how V25 seeded STUDENT_PROMOTE.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), 'HOMEWORK_MANAGE'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = 'HOMEWORK_MANAGE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN', 'TEACHER') AND p.name = 'HOMEWORK_MANAGE'
ON CONFLICT DO NOTHING;
