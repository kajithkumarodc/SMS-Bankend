-- Front Office / Admission Enquiry (Phase 2). Enquiry sources are a proper
-- lookup table (admin-editable from the UI), not a hardcoded CHECK enum --
-- the plan explicitly calls for sources to be configurable. Status stays a
-- CHECK constraint (a fixed, small pipeline: ACTIVE/FOLLOW_UP/WON/PASSIVE/
-- LOST/DEAD), matching the existing convention for fixed-set state columns
-- (students.status, invoices.status, leave_requests.status, ...).

CREATE TABLE enquiry_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT enquiry_sources_name_key UNIQUE (name)
);

INSERT INTO enquiry_sources (id, name) VALUES
    (gen_random_uuid(), 'Website'),
    (gen_random_uuid(), 'Walk-in'),
    (gen_random_uuid(), 'Phone'),
    (gen_random_uuid(), 'Referral'),
    (gen_random_uuid(), 'Advertisement'),
    (gen_random_uuid(), 'Other');

-- Sequence-backed enquiry numbers (ENQ-000001, ...): a real DB sequence
-- avoids the race condition a `count(*) + 1` scheme would have under
-- concurrent enquiry creation. Mirrors the "every payment must generate a
-- unique receipt" numbering concern from the fees module, applied here.
CREATE SEQUENCE enquiry_number_seq START WITH 1 INCREMENT BY 1;

-- V4's "future privileges" grant only covers TABLES, not SEQUENCES, so the
-- restricted runtime role (which the app actually connects as -- see V4)
-- would otherwise get "permission denied" calling nextval() here. Grant this
-- sequence explicitly, and extend the default-privileges rule to sequences
-- so the same gap doesn't recur for a later migration's sequence.
GRANT USAGE, SELECT ON SEQUENCE enquiry_number_seq TO ${app_user_name};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_user_name};

CREATE TABLE admission_enquiries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    enquiry_number VARCHAR(20) NOT NULL,
    applicant_name VARCHAR(200) NOT NULL,
    guardian_name VARCHAR(200),
    phone VARCHAR(30),
    email VARCHAR(200),
    -- Class/grade the applicant is interested in. Nullable -- an enquiry can
    -- predate a decision on grade. ON DELETE SET NULL: deleting a class must
    -- not cascade-delete enquiry history.
    class_id UUID REFERENCES classes(id) ON DELETE SET NULL,
    enquiry_date DATE NOT NULL DEFAULT CURRENT_DATE,
    source_id UUID REFERENCES enquiry_sources(id) ON DELETE SET NULL,
    assigned_staff_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Denormalized "latest follow-up" cache for list/dashboard views, kept in
    -- sync by the service whenever a row is added to enquiry_follow_ups --
    -- the full history lives in that table, not here.
    follow_up_date DATE,
    follow_up_notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remarks TEXT,
    -- Set once, at conversion. Stores the enquiry<->student relationship and
    -- -- via the UNIQUE constraint -- makes converting the same enquiry
    -- twice impossible (a clean 409, not a duplicate student).
    converted_student_id UUID REFERENCES students(id),
    -- Soft delete/archive, same pattern as students.status ACTIVE/INACTIVE --
    -- an enquiry is never hard-deleted so follow-up history stays intact.
    archived BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT admission_enquiries_enquiry_number_key UNIQUE (enquiry_number),
    CONSTRAINT admission_enquiries_converted_student_id_key UNIQUE (converted_student_id),
    CONSTRAINT admission_enquiries_status_check
        CHECK (status IN ('ACTIVE', 'FOLLOW_UP', 'WON', 'PASSIVE', 'LOST', 'DEAD'))
);

CREATE INDEX admission_enquiries_status_idx ON admission_enquiries(status);
CREATE INDEX admission_enquiries_class_id_idx ON admission_enquiries(class_id);
CREATE INDEX admission_enquiries_source_id_idx ON admission_enquiries(source_id);
CREATE INDEX admission_enquiries_assigned_staff_user_id_idx ON admission_enquiries(assigned_staff_user_id);
CREATE INDEX admission_enquiries_follow_up_date_idx ON admission_enquiries(follow_up_date);
CREATE INDEX admission_enquiries_phone_idx ON admission_enquiries(phone);
CREATE INDEX admission_enquiries_archived_idx ON admission_enquiries(archived);

CREATE TABLE enquiry_follow_ups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    enquiry_id UUID NOT NULL REFERENCES admission_enquiries(id) ON DELETE CASCADE,
    follow_up_date DATE NOT NULL,
    follow_up_type VARCHAR(20) NOT NULL,
    notes TEXT,
    staff_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    next_follow_up_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT enquiry_follow_ups_type_check
        CHECK (follow_up_type IN ('CALL', 'EMAIL', 'SMS', 'WHATSAPP', 'VISIT', 'OTHER'))
);

CREATE INDEX enquiry_follow_ups_enquiry_id_idx ON enquiry_follow_ups(enquiry_id);

-- RBAC Phase 1 permission catalog (V22) predates this module -- add the
-- Front Office permissions and grant them to the roles that need them.
-- SUPER_ADMIN/SCHOOL_ADMIN already get "every permission" via V22's
-- CROSS JOIN, but that INSERT only ran once at V22 time, so these new rows
-- need their own explicit grant here.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('ENQUIRY_VIEW'), ('ENQUIRY_CREATE'), ('ENQUIRY_EDIT'), ('ENQUIRY_DELETE'),
    ('ENQUIRY_FOLLOWUP'), ('ENQUIRY_CONVERT'), ('ENQUIRY_EXPORT'), ('ENQUIRY_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('ENQUIRY_VIEW', 'ENQUIRY_CREATE', 'ENQUIRY_EDIT', 'ENQUIRY_DELETE',
                 'ENQUIRY_FOLLOWUP', 'ENQUIRY_CONVERT', 'ENQUIRY_EXPORT', 'ENQUIRY_PRINT')
ON CONFLICT DO NOTHING;

-- RECEPTIONIST is the front-office role -- the day-to-day enquiry pipeline
-- (view/create/edit/follow-up/convert) is their core job. Archiving and
-- export/print stay admin-only.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'ENQUIRY_VIEW', 'ENQUIRY_CREATE', 'ENQUIRY_EDIT', 'ENQUIRY_FOLLOWUP', 'ENQUIRY_CONVERT'
)
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

-- PRINCIPAL gets oversight (view + reports) alongside their existing grants.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('ENQUIRY_VIEW', 'ENQUIRY_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
