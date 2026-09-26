-- Front Office: Visitor Book. One row per visit -- who came, why, whom they met
-- (a staff member or a student), when they came in and left, and an optional
-- attached document (e.g. a scan of their ID).

-- --- 1. Configurable visit purposes ---------------------------------------------
-- Same lookup-table shape as enquiry_sources (V23) / enquiry_references (V31).
-- Setup Front Office will manage these; defaults are seeded so the form works
-- from day one.

CREATE TABLE front_office_purposes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT front_office_purposes_name_key UNIQUE (name)
);

INSERT INTO front_office_purposes (id, name) VALUES
    (gen_random_uuid(), 'Parent Teacher Meeting'),
    (gen_random_uuid(), 'Principal Meeting'),
    (gen_random_uuid(), 'Staff Meeting'),
    (gen_random_uuid(), 'Student Meeting'),
    (gen_random_uuid(), 'School Events'),
    (gen_random_uuid(), 'Admission'),
    (gen_random_uuid(), 'Marketing'),
    (gen_random_uuid(), 'Other');

-- --- 2. Visitors ----------------------------------------------------------------

CREATE TABLE visitors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RESTRICT: a purpose that visits were logged under can be deactivated, not deleted.
    purpose_id UUID NOT NULL REFERENCES front_office_purposes(id) ON DELETE RESTRICT,
    -- Whom the visitor met: exactly one of staff_profile_id / student_id, matching the type.
    -- RESTRICT keeps the visit history from silently losing whom it was with.
    meeting_with_type VARCHAR(10) NOT NULL,
    staff_profile_id UUID REFERENCES staff_profiles(id) ON DELETE RESTRICT,
    student_id UUID REFERENCES students(id) ON DELETE RESTRICT,
    visitor_name VARCHAR(200) NOT NULL,
    phone VARCHAR(30),
    id_card VARCHAR(100),
    number_of_persons SMALLINT,
    visit_date DATE NOT NULL,
    in_time TIME,
    out_time TIME,
    note TEXT,
    -- Optional attached document. The file lives under app.storage.base-dir with a
    -- random name; the original filename is display-only (same rules as student_documents).
    attachment_original_filename VARCHAR(255),
    attachment_stored_filename VARCHAR(100),
    attachment_content_type VARCHAR(150),
    attachment_size_bytes BIGINT,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT visitors_meeting_with_check CHECK (
        (meeting_with_type = 'STAFF' AND staff_profile_id IS NOT NULL AND student_id IS NULL)
        OR (meeting_with_type = 'STUDENT' AND student_id IS NOT NULL AND staff_profile_id IS NULL)),
    CONSTRAINT visitors_number_of_persons_check CHECK (number_of_persons IS NULL OR number_of_persons BETWEEN 1 AND 999),
    CONSTRAINT visitors_out_after_in_check CHECK (in_time IS NULL OR out_time IS NULL OR out_time >= in_time),
    CONSTRAINT visitors_attachment_check CHECK (
        (attachment_stored_filename IS NULL) = (attachment_original_filename IS NULL))
);

CREATE INDEX visitors_visit_date_idx ON visitors(visit_date);
CREATE INDEX visitors_purpose_id_idx ON visitors(purpose_id);
CREATE INDEX visitors_staff_profile_id_idx ON visitors(staff_profile_id);
CREATE INDEX visitors_student_id_idx ON visitors(student_id);

-- --- 3. Permissions ---------------------------------------------------------------
-- Same pattern as V23: SUPER_ADMIN/SCHOOL_ADMIN get everything; RECEPTIONIST runs
-- the visitor desk day to day (delete stays admin-only, as for enquiries);
-- PRINCIPAL gets oversight.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('VISITOR_VIEW'), ('VISITOR_CREATE'), ('VISITOR_EDIT'), ('VISITOR_DELETE'),
    ('VISITOR_EXPORT'), ('VISITOR_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('VISITOR_VIEW', 'VISITOR_CREATE', 'VISITOR_EDIT', 'VISITOR_DELETE', 'VISITOR_EXPORT', 'VISITOR_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('VISITOR_VIEW', 'VISITOR_CREATE', 'VISITOR_EDIT')
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('VISITOR_VIEW', 'VISITOR_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
