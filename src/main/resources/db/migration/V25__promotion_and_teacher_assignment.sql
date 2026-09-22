-- Phase 4: Student Promotion + Parent & Teacher Panels. Three small, targeted
-- additions to EXISTING tables -- no new tables, per the explicit "do not
-- duplicate student_academic_history / promotion system" instruction.

-- 1) student_academic_history gains "who" and "why" for each placement change.
--    The append-only history table (Phase 3) already answers "what changed";
--    the Promotion History screen additionally needs "performed by" and needs
--    to tell a real PROMOTION apart from an admission or a manual section
--    reassignment. Existing rows predate this distinction and are backfilled
--    as MANUAL_ASSIGNMENT (the closest honest default -- we cannot retroactively
--    know which were promotions).
ALTER TABLE student_academic_history
    ADD COLUMN recorded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN change_reason VARCHAR(20) NOT NULL DEFAULT 'MANUAL_ASSIGNMENT';

ALTER TABLE student_academic_history ADD CONSTRAINT student_academic_history_change_reason_check
    CHECK (change_reason IN ('ADMISSION', 'MANUAL_ASSIGNMENT', 'PROMOTION'));

CREATE INDEX student_academic_history_academic_year_id_idx ON student_academic_history(academic_year_id);

-- 2) class_subjects gains an optional teacher -- the "assignment structure"
--    Part C's Teacher Panel needs and the codebase does not otherwise have
--    (confirmed: no existing teacher/class/section link anywhere). Reuses the
--    EXISTING class<->subject table instead of a new one, matching the plan's
--    own "Academic Session -> Class -> Section -> Subject -> Teacher -> Students"
--    hierarchy -- Teacher was always meant to hang off this table.
ALTER TABLE class_subjects ADD COLUMN teacher_id UUID REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX class_subjects_teacher_id_idx ON class_subjects(teacher_id);

-- 3) RBAC Phase 1 permission for the promotion action specifically (plan:
--    "verify/create only the permissions actually required, such as
--    STUDENT_PROMOTE"). STUDENT_VIEW already exists (V22) and is reused as-is.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), 'STUDENT_PROMOTE'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = 'STUDENT_PROMOTE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'STUDENT_PROMOTE'
ON CONFLICT DO NOTHING;
