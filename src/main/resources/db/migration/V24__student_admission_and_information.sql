-- Phase 3: Student Admission + Student Information. Extends the existing
-- `students` table (does not replace it) with the remaining admission-form
-- fields, plus three new append-only/child tables: configurable
-- identification documents, academic history (Student -> Academic Session ->
-- Class -> Section, never overwritten by promotion), and uploaded document
-- metadata (files themselves live on local disk -- see StudentDocumentService
-- -- never a publicly-served path, never a paid cloud bucket).

-- --- Personal / admission / address fields -----------------------------

ALTER TABLE students
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN middle_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100),
    ADD COLUMN photo_url TEXT,
    ADD COLUMN religion VARCHAR(100),
    -- Free text, not a hardcoded enum -- admission categories vary too much
    -- by country/board to bake into a CHECK constraint (mirrors the V23
    -- enquiry_sources "configurable, not hardcoded" decision).
    ADD COLUMN category VARCHAR(50),
    ADD COLUMN enrollment_number VARCHAR(60),
    ADD COLUMN previous_school_name VARCHAR(200),
    ADD COLUMN previous_school_class VARCHAR(50),
    ADD COLUMN previous_school_admission_number VARCHAR(60),
    ADD COLUMN previous_school_address TEXT,
    ADD COLUMN transfer_certificate_number VARCHAR(100),
    ADD COLUMN admission_source VARCHAR(100),
    ADD COLUMN rte_status BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN current_country VARCHAR(100),
    ADD COLUMN permanent_address_line1 VARCHAR(255),
    ADD COLUMN permanent_address_line2 VARCHAR(255),
    ADD COLUMN permanent_city VARCHAR(100),
    ADD COLUMN permanent_state VARCHAR(100),
    ADD COLUMN permanent_country VARCHAR(100),
    ADD COLUMN permanent_pincode VARCHAR(10),
    ADD COLUMN emergency_contact_alternate_mobile VARCHAR(30),
    ADD COLUMN emergency_contact_address TEXT,
    -- Sibling grouping key: students sharing a family_id are treated as
    -- siblings (plan: "a parent can have multiple students... the
    -- relationship must be stored properly"). Not a FK to a "families" table
    -- -- there is no separate family entity, this is just a shared grouping
    -- UUID, generated for the first child and reused for each sibling added
    -- afterward. Nullable: most students have no linked siblings.
    ADD COLUMN family_id UUID;

-- Best-effort backfill of the existing single `full_name` column into
-- first/last (split on the first space) so first_name/last_name can become
-- NOT NULL without losing any existing student's name. Going forward,
-- full_name itself is always derived server-side from first/middle/last
-- (StudentService composes it) -- every existing reader of `students.full_name`
-- / `StudentResponse.fullName` keeps working unchanged.
UPDATE students SET
    first_name = COALESCE(NULLIF(split_part(full_name, ' ', 1), ''), full_name, 'Unknown'),
    last_name = CASE
        WHEN position(' ' IN full_name) > 0 THEN trim(substring(full_name FROM position(' ' IN full_name) + 1))
        ELSE ''
    END
WHERE first_name IS NULL;

ALTER TABLE students
    ALTER COLUMN first_name SET NOT NULL,
    ALTER COLUMN last_name SET NOT NULL;

CREATE INDEX students_family_id_idx ON students(family_id) WHERE family_id IS NOT NULL;
CREATE UNIQUE INDEX students_enrollment_number_key ON students(enrollment_number) WHERE enrollment_number IS NOT NULL;

-- Widen the status pipeline (was ACTIVE/INACTIVE only). No student is ever
-- physically deleted -- these are additional terminal/near-terminal states,
-- same soft-delete philosophy as the existing ACTIVE/INACTIVE toggle.
ALTER TABLE students DROP CONSTRAINT students_status_check;
ALTER TABLE students ADD CONSTRAINT students_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'GRADUATED', 'LEFT_SCHOOL', 'TRANSFERRED'));

-- --- Configurable identification fields ---------------------------------
-- e.g. NATIONAL_ID, LOCAL_ID, BIRTH_CERTIFICATE, OTHER -- free text id_type,
-- not a hardcoded country-specific column, per the plan's explicit
-- "do not unnecessarily hardcode country-specific IDs".

CREATE TABLE student_identifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    id_type VARCHAR(50) NOT NULL,
    id_value VARCHAR(200) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX student_identifications_student_id_idx ON student_identifications(student_id);

-- --- Academic history: Student -> Academic Session -> Class -> Section ---
-- Append-only. StudentService/AcademicYearService insert a row here whenever
-- a student's section changes (initial admission, manual reassignment, or
-- year-end promotion) -- students.section_id is only ever "where they are
-- now"; this table is "where they've been each year", never overwritten.

CREATE TABLE student_academic_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    academic_year_id UUID REFERENCES academic_years(id) ON DELETE SET NULL,
    class_id UUID REFERENCES classes(id) ON DELETE SET NULL,
    section_id UUID REFERENCES sections(id) ON DELETE SET NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX student_academic_history_student_id_idx ON student_academic_history(student_id);

-- --- Student documents (metadata only; files live on local disk) --------

CREATE TABLE student_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    -- Free text (examples: BIRTH_CERTIFICATE, TRANSFER_CERTIFICATE,
    -- PREVIOUS_SCHOOL_CERTIFICATE, STUDENT_ID, GUARDIAN_ID, OTHER) -- the
    -- plan lists these as examples, not an exhaustive/fixed set.
    document_type VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    -- The actual on-disk filename: a random UUID + validated extension,
    -- never derived from user input -- prevents path traversal and
    -- collisions. original_filename is metadata only, never used as a path.
    stored_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    uploaded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    notes VARCHAR(500),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT student_documents_stored_filename_key UNIQUE (stored_filename)
);

CREATE INDEX student_documents_student_id_idx ON student_documents(student_id);

-- --- RBAC Phase 1 permissions: document-specific grants -------------------
-- STUDENT_VIEW/CREATE/EDIT/DELETE/EXPORT/PRINT already exist (V22) and are
-- already granted to the roles that need them -- StudentController is being
-- switched from role-based to permission-based checks in this phase (no new
-- grants needed for that). Documents get their own finer-grained set.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('STUDENT_DOCUMENT_VIEW'), ('STUDENT_DOCUMENT_UPLOAD'), ('STUDENT_DOCUMENT_DELETE')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('STUDENT_DOCUMENT_VIEW', 'STUDENT_DOCUMENT_UPLOAD', 'STUDENT_DOCUMENT_DELETE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('STUDENT_DOCUMENT_VIEW', 'STUDENT_DOCUMENT_UPLOAD')
WHERE r.name IN ('TEACHER', 'RECEPTIONIST')
ON CONFLICT DO NOTHING;
