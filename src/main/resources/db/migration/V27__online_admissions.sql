-- Phase 4.5: Online Admissions & Enrollment Workflow. A genuinely new domain --
-- no existing table covers "a public applicant submits before becoming a
-- student" -- built to hand off into the EXISTING Phase 3 student-admission
-- path unchanged: approval calls StudentService.create(...) with fields
-- mapped from the application, so there is exactly one place a student row
-- is ever created (same reuse discipline as EnquiryService#convertToStudent).

-- 1) Admission cycles ("2026-27 Admissions"): scoped to a school + an existing
-- academic year (never a parallel session concept). At most one OPEN cycle per
-- school at a time -- same partial-unique-index pattern as
-- academic_years.is_current (V22).
CREATE TABLE admission_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id UUID NOT NULL REFERENCES schools(id),
    academic_year_id UUID NOT NULL REFERENCES academic_years(id),
    name VARCHAR(150) NOT NULL,
    description VARCHAR(1000),
    open_date DATE NOT NULL,
    close_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT admission_cycles_status_check CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    CONSTRAINT admission_cycles_date_range_check CHECK (close_date > open_date)
);

CREATE INDEX admission_cycles_school_id_idx ON admission_cycles(school_id);
CREATE INDEX admission_cycles_academic_year_id_idx ON admission_cycles(academic_year_id);
CREATE UNIQUE INDEX admission_cycles_single_open_per_school_idx ON admission_cycles(school_id) WHERE status = 'OPEN';

-- 2) Applications. Every "Student information"/"Guardian"/"Previous school"/
-- "Address" field here exists ONLY so approval can build a Phase 3
-- CreateStudentRequest automatically -- deliberately the same field set,
-- never a second student model. Status is a plain string column (not a JPA
-- enum) with this CHECK as the source of truth, matching every other
-- status/category column in the app (InvoiceStatus, EnquiryStatus, ...).
CREATE TABLE admission_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_number VARCHAR(30) NOT NULL,
    admission_cycle_id UUID NOT NULL REFERENCES admission_cycles(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',

    -- student information
    first_name VARCHAR(100) NOT NULL,
    middle_name VARCHAR(100),
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(10),
    blood_group VARCHAR(5),
    nationality VARCHAR(100),
    religion VARCHAR(100),
    mother_tongue VARCHAR(100),
    category VARCHAR(50),

    -- admission details
    applying_class_id UUID REFERENCES classes(id),
    previous_school_name VARCHAR(200),
    previous_school_class VARCHAR(50),
    previous_school_admission_number VARCHAR(60),
    previous_school_address VARCHAR(500),
    admission_source VARCHAR(100),

    -- guardian
    guardian_name VARCHAR(200),
    guardian_relationship VARCHAR(20),
    guardian_phone VARCHAR(20),
    guardian_alternate_phone VARCHAR(20),
    guardian_email VARCHAR(200),
    guardian_occupation VARCHAR(200),
    father_name VARCHAR(200),
    father_mobile VARCHAR(20),
    father_email VARCHAR(200),
    father_occupation VARCHAR(200),
    mother_name VARCHAR(200),
    mother_mobile VARCHAR(20),
    mother_email VARCHAR(200),
    mother_occupation VARCHAR(200),

    -- address
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city VARCHAR(100),
    state VARCHAR(100),
    country VARCHAR(100),
    pincode VARCHAR(10),

    -- review / conversion
    reviewed_at TIMESTAMPTZ,
    reviewed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewer_notes VARCHAR(2000),
    converted_student_id UUID REFERENCES students(id),
    converted_guardian_user_id UUID REFERENCES users(id),

    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT admission_applications_number_key UNIQUE (application_number),
    CONSTRAINT admission_applications_status_check
        CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'WAITLISTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT admission_applications_gender_check CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    -- Idempotent conversion (plan part 12): once converted, always to the same one student.
    CONSTRAINT admission_applications_converted_student_key UNIQUE (converted_student_id)
);

CREATE INDEX admission_applications_cycle_id_idx ON admission_applications(admission_cycle_id);
CREATE INDEX admission_applications_school_id_idx ON admission_applications(school_id);
CREATE INDEX admission_applications_status_idx ON admission_applications(status);
CREATE INDEX admission_applications_submitted_at_idx ON admission_applications(submitted_at);
CREATE INDEX admission_applications_applying_class_id_idx ON admission_applications(applying_class_id);
CREATE INDEX admission_applications_guardian_email_idx ON admission_applications(guardian_email);
CREATE INDEX admission_applications_converted_student_id_idx ON admission_applications(converted_student_id);

-- Public reference numbers are randomized-looking (APP-YYYY-NNNNNN from a
-- sequence, not row count), same reasoning as enquiry_number_seq (V23) --
-- collision-proof under concurrent submissions, though the point here is
-- collision-proofing rather than secrecy (the lookup endpoint also demands
-- the applicant's own email before revealing anything, per plan part 15).
CREATE SEQUENCE admission_application_number_seq START WITH 1 INCREMENT BY 1;

-- Auto-generated student admission numbers for approved applications get their
-- own sequence/prefix, distinct from application_number, so the two never
-- look alike or collide (StudentService.create still 409s on any accidental
-- clash against a manually-typed admission number, same safety net as ever).
CREATE SEQUENCE student_admission_number_seq START WITH 1 INCREMENT BY 1;

-- 3) Application documents -- same shape and same local/self-hosted storage
-- root (app.storage.base-dir) as student_documents (V24), just a different
-- subfolder (applications/{id}/...) since the application isn't a student yet.
CREATE TABLE admission_application_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES admission_applications(id) ON DELETE CASCADE,
    document_type VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    notes VARCHAR(500),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT admission_application_documents_stored_filename_key UNIQUE (stored_filename)
);

CREATE INDEX admission_application_documents_application_id_idx ON admission_application_documents(application_id);

-- 4) Optional link back to a Phase 2 enquiry that led to this application
-- (plan part 17) -- informational only, same "set once, never enforced"
-- pattern as admission_enquiries.converted_student_id itself.
ALTER TABLE admission_enquiries ADD COLUMN admission_application_id UUID REFERENCES admission_applications(id);

-- 5) One-time account-activation tokens (plan part 14): lets a newly-created
-- parent user set their own password via an emailed link instead of the
-- existing admin-facing "temporary password shown once in the UI" flow
-- (UserService#create) being the only option -- appropriate for a portal
-- invite email, never a paid service, no plaintext password ever emailed.
CREATE TABLE user_activation_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_activation_tokens_token_key UNIQUE (token)
);

CREATE INDEX user_activation_tokens_user_id_idx ON user_activation_tokens(user_id);

-- V23's fix already extends ALTER DEFAULT PRIVILEGES to cover future
-- sequences for the restricted runtime role, so no separate GRANT is needed
-- for the two new sequences above.

-- 6) New permissions (plan part 19), following the existing <MODULE>_<ACTION>
-- catalog (V22). Granted to SUPER_ADMIN/SCHOOL_ADMIN only for now, matching
-- the same admin-only-by-default precedent as STUDENT_PROMOTE (V25) -- an
-- admin can broaden this later from the Roles screen.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('ADMISSION_APPLICATION_VIEW'), ('ADMISSION_APPLICATION_CREATE'), ('ADMISSION_APPLICATION_EDIT'),
    ('ADMISSION_APPLICATION_REVIEW'), ('ADMISSION_APPLICATION_APPROVE'), ('ADMISSION_APPLICATION_REJECT'),
    ('ADMISSION_APPLICATION_WAITLIST'), ('ADMISSION_APPLICATION_DOCUMENT_VIEW'),
    ('ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD'), ('ADMISSION_APPLICATION_EXPORT'),
    ('ADMISSION_CYCLE_VIEW'), ('ADMISSION_CYCLE_CREATE'), ('ADMISSION_CYCLE_EDIT'),
    ('ADMISSION_CYCLE_OPEN'), ('ADMISSION_CYCLE_CLOSE')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN (
    'ADMISSION_APPLICATION_VIEW', 'ADMISSION_APPLICATION_CREATE', 'ADMISSION_APPLICATION_EDIT',
    'ADMISSION_APPLICATION_REVIEW', 'ADMISSION_APPLICATION_APPROVE', 'ADMISSION_APPLICATION_REJECT',
    'ADMISSION_APPLICATION_WAITLIST', 'ADMISSION_APPLICATION_DOCUMENT_VIEW',
    'ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD', 'ADMISSION_APPLICATION_EXPORT',
    'ADMISSION_CYCLE_VIEW', 'ADMISSION_CYCLE_CREATE', 'ADMISSION_CYCLE_EDIT',
    'ADMISSION_CYCLE_OPEN', 'ADMISSION_CYCLE_CLOSE'
)
ON CONFLICT DO NOTHING;
