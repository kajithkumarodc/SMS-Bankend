-- Front Office: Postal Receive. Letters and parcels the school receives, each with an
-- auto-generated reference number (PRC-YYYY-NNNNN, from document_number_counters,
-- V34) and any number of supporting documents. Mirrors Postal Dispatch (V34).

CREATE TABLE postal_receives (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- PRC-<academic year start>-<5-digit sequence>, e.g. PRC-2026-00001. Assigned once
    -- on creation and never editable.
    reference_no VARCHAR(20) NOT NULL,
    from_title VARCHAR(200) NOT NULL,
    to_title VARCHAR(200),
    address TEXT,
    note TEXT,
    receive_date DATE NOT NULL,
    -- The academic year the item was received in, when that year has been set up.
    academic_year_id UUID REFERENCES academic_years(id) ON DELETE SET NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT postal_receives_reference_no_key UNIQUE (reference_no)
);

CREATE INDEX postal_receives_receive_date_idx ON postal_receives(receive_date);

-- Files live under app.storage.base-dir/postal-receives/<receive id>/ with random names.
CREATE TABLE postal_receive_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receive_id UUID NOT NULL REFERENCES postal_receives(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(100) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX postal_receive_documents_receive_id_idx ON postal_receive_documents(receive_id);

-- Permissions, same split as Postal Dispatch.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('POSTAL_RECEIVE_VIEW'), ('POSTAL_RECEIVE_CREATE'), ('POSTAL_RECEIVE_EDIT'), ('POSTAL_RECEIVE_DELETE'),
    ('POSTAL_RECEIVE_EXPORT'), ('POSTAL_RECEIVE_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('POSTAL_RECEIVE_VIEW', 'POSTAL_RECEIVE_CREATE', 'POSTAL_RECEIVE_EDIT', 'POSTAL_RECEIVE_DELETE',
                 'POSTAL_RECEIVE_EXPORT', 'POSTAL_RECEIVE_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r
JOIN permissions p ON p.name IN ('POSTAL_RECEIVE_VIEW', 'POSTAL_RECEIVE_CREATE', 'POSTAL_RECEIVE_EDIT')
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('POSTAL_RECEIVE_VIEW', 'POSTAL_RECEIVE_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
