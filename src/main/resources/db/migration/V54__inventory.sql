-- Inventory: item categories, items with their stock, and the issue / return of items to staff.

CREATE TABLE inventory_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_categories_name_key UNIQUE (name)
);

CREATE TABLE inventory_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    category_id UUID NOT NULL REFERENCES inventory_categories(id),
    -- Units on the shelf: issuing takes from it, returning gives back.
    stock INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_items_stock_check CHECK (stock >= 0),
    CONSTRAINT inventory_items_name_category_key UNIQUE (name, category_id)
);

CREATE TABLE inventory_issues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id UUID NOT NULL REFERENCES inventory_items(id),
    quantity INTEGER NOT NULL,
    issue_to_staff_id UUID NOT NULL REFERENCES staff_profiles(id),
    issued_by_staff_id UUID NOT NULL REFERENCES staff_profiles(id),
    issue_date DATE NOT NULL,
    -- When the item is expected back.
    return_date DATE,
    note VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    returned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_issues_quantity_check CHECK (quantity > 0),
    CONSTRAINT inventory_issues_status_check CHECK (status IN ('ISSUED', 'RETURNED')),
    CONSTRAINT inventory_issues_dates_check CHECK (return_date IS NULL OR return_date >= issue_date)
);

CREATE INDEX inventory_issues_issue_date_idx ON inventory_issues(issue_date DESC);
CREATE INDEX inventory_issues_item_idx ON inventory_issues(item_id);

INSERT INTO inventory_categories (id, name) VALUES
    (gen_random_uuid(), 'Sports'),
    (gen_random_uuid(), 'Staff Dress'),
    (gen_random_uuid(), 'Furniture'),
    (gen_random_uuid(), 'Books Stationery'),
    (gen_random_uuid(), 'Chemistry Lab Apparatus');

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('INVENTORY_VIEW'), ('INVENTORY_ISSUE'), ('INVENTORY_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name IN ('INVENTORY_VIEW', 'INVENTORY_ISSUE', 'INVENTORY_MANAGE')
ON CONFLICT DO NOTHING;
