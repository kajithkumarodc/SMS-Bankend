-- Annual Calendar: the types of calendar entries (Holiday, Vacation, ...) and the entries themselves.

CREATE TABLE holiday_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT holiday_types_name_key UNIQUE (name)
);

INSERT INTO holiday_types (id, name) VALUES
    (gen_random_uuid(), 'Holiday'),
    (gen_random_uuid(), 'Vacation'),
    (gen_random_uuid(), 'Activity'),
    (gen_random_uuid(), 'EVENTS'),
    (gen_random_uuid(), 'School Events');

CREATE TABLE calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holiday_type_id UUID NOT NULL REFERENCES holiday_types(id),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    description VARCHAR(1000) NOT NULL,
    -- Whether the entry is shown on the school's public website.
    front_site BOOLEAN NOT NULL DEFAULT false,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT calendar_events_dates_check CHECK (to_date >= from_date)
);

CREATE INDEX calendar_events_from_date_idx ON calendar_events(from_date DESC);

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('CALENDAR_VIEW'), ('CALENDAR_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name IN ('CALENDAR_VIEW', 'CALENDAR_MANAGE')
ON CONFLICT DO NOTHING;

-- Principals and teachers can read the calendar.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'CALENDAR_VIEW'
WHERE r.name IN ('PRINCIPAL', 'TEACHER')
ON CONFLICT DO NOTHING;
