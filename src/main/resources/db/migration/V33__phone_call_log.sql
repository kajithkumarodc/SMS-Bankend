-- Front Office: Phone Call Log. One row per incoming or outgoing call the front
-- desk handled -- who, which number, when, what it was about, and when to call back.

CREATE TABLE phone_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Caller/callee name. Optional -- the number is what's always known.
    name VARCHAR(200),
    phone VARCHAR(30) NOT NULL,
    call_date DATE NOT NULL,
    description TEXT,
    next_follow_up_date DATE,
    -- Free text as the desk records it ("5 min", "00:12:30"), not a parsed interval.
    call_duration VARCHAR(50),
    note TEXT,
    call_type VARCHAR(10) NOT NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT phone_call_logs_call_type_check CHECK (call_type IN ('INCOMING', 'OUTGOING')),
    CONSTRAINT phone_call_logs_follow_up_check CHECK (next_follow_up_date IS NULL OR next_follow_up_date >= call_date)
);

CREATE INDEX phone_call_logs_call_date_idx ON phone_call_logs(call_date);
CREATE INDEX phone_call_logs_phone_idx ON phone_call_logs(phone);

-- Permissions, same split as the Visitor Book (V32): admins everything, the
-- receptionist runs the call log day to day (delete stays admin-only), the
-- principal gets oversight.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('PHONE_CALL_VIEW'), ('PHONE_CALL_CREATE'), ('PHONE_CALL_EDIT'), ('PHONE_CALL_DELETE'),
    ('PHONE_CALL_EXPORT'), ('PHONE_CALL_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('PHONE_CALL_VIEW', 'PHONE_CALL_CREATE', 'PHONE_CALL_EDIT', 'PHONE_CALL_DELETE',
                 'PHONE_CALL_EXPORT', 'PHONE_CALL_PRINT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('PHONE_CALL_VIEW', 'PHONE_CALL_CREATE', 'PHONE_CALL_EDIT')
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('PHONE_CALL_VIEW', 'PHONE_CALL_EXPORT')
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;
