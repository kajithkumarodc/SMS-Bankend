-- Expenses: money the school spends, filed under a configurable expense head, with an optional
-- attached invoice or receipt.

-- --- 1. Configurable expense heads ------------------------------------------------------
-- Same lookup-table shape as fee_types (V26) and complaint_types (V36).

CREATE TABLE expense_heads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT expense_heads_name_key UNIQUE (name)
);

INSERT INTO expense_heads (id, name) VALUES
    (gen_random_uuid(), 'Stationery Purchase'),
    (gen_random_uuid(), 'Electricity Bill'),
    (gen_random_uuid(), 'Telephone Bill'),
    (gen_random_uuid(), 'Miscellaneous'),
    (gen_random_uuid(), 'Flower'),
    (gen_random_uuid(), 'Maintenance');

-- --- 2. Expenses --------------------------------------------------------------------------

CREATE TABLE expenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RESTRICT: deactivate a head rather than delete one that has expenses.
    expense_head_id UUID NOT NULL REFERENCES expense_heads(id) ON DELETE RESTRICT,
    name VARCHAR(200) NOT NULL,
    invoice_number VARCHAR(100),
    expense_date DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    description TEXT,
    -- Optional attached document, stored under app.storage.base-dir/expenses/<id>/.
    attachment_original_filename VARCHAR(255),
    attachment_stored_filename VARCHAR(100),
    attachment_content_type VARCHAR(150),
    attachment_size_bytes BIGINT,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT expenses_amount_check CHECK (amount > 0),
    CONSTRAINT expenses_attachment_check CHECK (
        (attachment_stored_filename IS NULL) = (attachment_original_filename IS NULL))
);

CREATE INDEX expenses_expense_date_idx ON expenses(expense_date);
CREATE INDEX expenses_expense_head_id_idx ON expenses(expense_head_id);

-- --- 3. Permissions -----------------------------------------------------------------------

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('EXPENSE_VIEW'), ('EXPENSE_CREATE'), ('EXPENSE_EDIT'), ('EXPENSE_DELETE'),
    ('EXPENSE_EXPORT'), ('EXPENSE_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('EXPENSE_VIEW', 'EXPENSE_CREATE', 'EXPENSE_EDIT', 'EXPENSE_DELETE',
                 'EXPENSE_EXPORT', 'EXPENSE_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('EXPENSE_VIEW', 'EXPENSE_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
