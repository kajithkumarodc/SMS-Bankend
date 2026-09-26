-- Front Office: Postal Dispatch. Letters and parcels the school sends out, each with
-- an auto-generated reference number and any number of supporting documents.

-- --- 1. Per-year document number counters --------------------------------------
-- One row per (series, year), incremented with a single atomic
-- INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING, so concurrent saves can
-- never be handed the same number, and a rolled-back save doesn't use one up.
-- Reusable by any other numbered document (e.g. Postal Receive).

CREATE TABLE document_number_counters (
    series VARCHAR(20) NOT NULL,
    year INTEGER NOT NULL,
    last_value INTEGER NOT NULL,
    PRIMARY KEY (series, year),
    CONSTRAINT document_number_counters_last_value_check CHECK (last_value > 0)
);

-- --- 2. Dispatches ------------------------------------------------------------------

CREATE TABLE postal_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- DSP-<academic year start>-<5-digit sequence>, e.g. DSP-2026-00001. Assigned once
    -- on creation and never editable.
    reference_no VARCHAR(20) NOT NULL,
    to_title VARCHAR(200) NOT NULL,
    from_title VARCHAR(200),
    address TEXT,
    note TEXT,
    dispatch_date DATE NOT NULL,
    -- The academic year that was current when the dispatch was recorded (the year in reference_no).
    academic_year_id UUID REFERENCES academic_years(id) ON DELETE SET NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT postal_dispatches_reference_no_key UNIQUE (reference_no)
);

CREATE INDEX postal_dispatches_dispatch_date_idx ON postal_dispatches(dispatch_date);

-- --- 3. Supporting documents (many per dispatch) -------------------------------------
-- Files live under app.storage.base-dir/postal-dispatches/<dispatch id>/ with random
-- names; original_filename is display-only. CASCADE: the rows go with the dispatch
-- (the service deletes the files themselves).

CREATE TABLE postal_dispatch_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_id UUID NOT NULL REFERENCES postal_dispatches(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(100) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX postal_dispatch_documents_dispatch_id_idx ON postal_dispatch_documents(dispatch_id);

-- --- 4. Permissions ------------------------------------------------------------------
-- Same split as the Visitor Book (V32) and Phone Call Log (V33).

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('POSTAL_DISPATCH_VIEW'), ('POSTAL_DISPATCH_CREATE'), ('POSTAL_DISPATCH_EDIT'), ('POSTAL_DISPATCH_DELETE'),
    ('POSTAL_DISPATCH_EXPORT'), ('POSTAL_DISPATCH_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('POSTAL_DISPATCH_VIEW', 'POSTAL_DISPATCH_CREATE', 'POSTAL_DISPATCH_EDIT', 'POSTAL_DISPATCH_DELETE',
                 'POSTAL_DISPATCH_EXPORT', 'POSTAL_DISPATCH_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.name IN ('POSTAL_DISPATCH_VIEW', 'POSTAL_DISPATCH_CREATE', 'POSTAL_DISPATCH_EDIT')
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('POSTAL_DISPATCH_VIEW', 'POSTAL_DISPATCH_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
