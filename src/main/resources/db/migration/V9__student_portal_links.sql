-- Portal self-service links (plan section 2, "Portals: parent portal, student portal").
--
-- A student row can be linked to:
--   - guardian_user_id: a PARENT user account (a parent may have several children,
--     so this is NOT unique).
--   - student_user_id: the student's own STUDENT-role login, if they have one
--     (one login maps to at most one student, so it is unique per tenant; NULLs
--     are allowed and PostgreSQL permits many NULLs in a UNIQUE constraint).
--
-- Both are nullable -- a student may have neither link yet. `students` already
-- carries tenant RLS from V5; no policy change is needed.

ALTER TABLE students ADD COLUMN guardian_user_id UUID REFERENCES users(id);
ALTER TABLE students ADD COLUMN student_user_id UUID REFERENCES users(id);

ALTER TABLE students ADD CONSTRAINT students_tenant_student_user_key UNIQUE (tenant_id, student_user_id);

-- tenant_id-leading, matching the portal lookup access paths.
CREATE INDEX students_tenant_guardian_user_id_idx ON students(tenant_id, guardian_user_id);
