-- Front Office: Complaints. Who complained, about what, what was done, and an optional
-- attached document.

-- --- 1. Configurable complaint types ------------------------------------------------
-- Same lookup-table shape as front_office_purposes (V32). Setup Front Office will
-- manage these; the defaults match the school's current categories.

CREATE TABLE complaint_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT complaint_types_name_key UNIQUE (name)
);

INSERT INTO complaint_types (id, name) VALUES
    (gen_random_uuid(), 'Fees'),
    (gen_random_uuid(), 'Study'),
    (gen_random_uuid(), 'Teacher'),
    (gen_random_uuid(), 'Sports'),
    (gen_random_uuid(), 'Transport'),
    (gen_random_uuid(), 'Hostel'),
    (gen_random_uuid(), 'Front Office');

-- --- 2. Shared Front Office sources ---------------------------------------------------
-- Complaints use the same "Source" list as admission enquiries (enquiry_sources, V23),
-- as Setup Front Office has one Source list. Add the sources the school uses that the
-- V23 defaults didn't include; existing names are left untouched.

INSERT INTO enquiry_sources (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('Online Front Site'), ('Google Ads'), ('Admission Campaign'), ('Front Office')
) AS new_sources(name)
WHERE NOT EXISTS (SELECT 1 FROM enquiry_sources s WHERE s.name = new_sources.name);

-- --- 3. Complaints ----------------------------------------------------------------------

CREATE TABLE complaints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- "Complain #": a plain, database-assigned sequential number, never editable.
    complaint_no BIGINT GENERATED ALWAYS AS IDENTITY,
    -- RESTRICT/SET NULL: deactivate a type or source rather than delete one that's in use;
    -- if a source is removed the complaint keeps everything else.
    complaint_type_id UUID REFERENCES complaint_types(id) ON DELETE RESTRICT,
    source_id UUID REFERENCES enquiry_sources(id) ON DELETE SET NULL,
    complain_by VARCHAR(200) NOT NULL,
    phone VARCHAR(30),
    complaint_date DATE NOT NULL,
    description TEXT,
    action_taken VARCHAR(500),
    -- Free text, as on the form (e.g. a staff member's name).
    assigned VARCHAR(200),
    note TEXT,
    -- Optional attached document, stored under app.storage.base-dir/complaints/<id>/.
    attachment_original_filename VARCHAR(255),
    attachment_stored_filename VARCHAR(100),
    attachment_content_type VARCHAR(150),
    attachment_size_bytes BIGINT,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT complaints_complaint_no_key UNIQUE (complaint_no),
    CONSTRAINT complaints_attachment_check CHECK (
        (attachment_stored_filename IS NULL) = (attachment_original_filename IS NULL))
);

CREATE INDEX complaints_complaint_date_idx ON complaints(complaint_date);
CREATE INDEX complaints_complaint_type_id_idx ON complaints(complaint_type_id);

-- --- 4. Permissions ---------------------------------------------------------------------
-- Same split as the other Front Office pages.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('COMPLAINT_VIEW'), ('COMPLAINT_CREATE'), ('COMPLAINT_EDIT'), ('COMPLAINT_DELETE'),
    ('COMPLAINT_EXPORT'), ('COMPLAINT_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('COMPLAINT_VIEW', 'COMPLAINT_CREATE', 'COMPLAINT_EDIT', 'COMPLAINT_DELETE',
                 'COMPLAINT_EXPORT', 'COMPLAINT_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('COMPLAINT_VIEW', 'COMPLAINT_CREATE', 'COMPLAINT_EDIT')
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('COMPLAINT_VIEW', 'COMPLAINT_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
