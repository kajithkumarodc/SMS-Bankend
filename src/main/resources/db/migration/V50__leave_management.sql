-- Human Resource -> Approve Leave Request: leave types, half-day leave, days, apply date, note, an optional
-- attached document, and who decided the request. Builds on V17's leave_requests (status PENDING / APPROVED /
-- REJECTED -- shown as "Disapproved").

-- --- 1. Configurable leave types ---------------------------------------------------------------
-- Same lookup-table shape as expense_heads (V46). The defaults match the leave entitlements on the Add Staff
-- form (V47); the Leave Type page will manage the list.

CREATE TABLE leave_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT leave_types_name_key UNIQUE (name)
);

INSERT INTO leave_types (id, name) VALUES
    (gen_random_uuid(), 'Medical Leave'),
    (gen_random_uuid(), 'Casual Leave'),
    (gen_random_uuid(), 'Maternity Leave'),
    (gen_random_uuid(), 'Sick Leave'),
    (gen_random_uuid(), 'Mandatory Leave');

-- Keep any free-text leave type already on a request (V17) as a list entry.
INSERT INTO leave_types (id, name)
SELECT gen_random_uuid(), t.name FROM (
    SELECT DISTINCT btrim(leave_type) AS name FROM leave_requests WHERE btrim(leave_type) <> ''
) t
WHERE NOT EXISTS (SELECT 1 FROM leave_types x WHERE x.name = t.name);

-- --- 2. Leave request columns ----------------------------------------------------------------------
-- `leave_type` (text) stays as the display name the older leave API reads; the new pages set it together with
-- `leave_type_id`.

ALTER TABLE leave_requests
    ADD COLUMN leave_type_id UUID REFERENCES leave_types(id) ON DELETE SET NULL,
    ADD COLUMN half_day VARCHAR(20),
    ADD COLUMN days NUMERIC(5, 2),
    ADD COLUMN apply_date DATE,
    ADD COLUMN note VARCHAR(2000),
    ADD COLUMN attachment_original_filename VARCHAR(255),
    ADD COLUMN attachment_stored_filename VARCHAR(100),
    ADD COLUMN attachment_content_type VARCHAR(150),
    ADD COLUMN attachment_size_bytes BIGINT,
    ADD COLUMN decided_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN decided_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE leave_requests r SET leave_type_id = t.id FROM leave_types t WHERE t.name = btrim(r.leave_type);
UPDATE leave_requests SET days = (end_date - start_date) + 1, apply_date = created_at::date;

ALTER TABLE leave_requests
    ALTER COLUMN days SET NOT NULL,
    ALTER COLUMN apply_date SET NOT NULL,
    ADD CONSTRAINT leave_requests_half_day_check CHECK (half_day IS NULL OR half_day IN ('FIRST_HALF', 'SECOND_HALF')),
    -- A half day is one day, counted as 0.5; otherwise the days are the calendar days from start to end.
    ADD CONSTRAINT leave_requests_half_day_dates_check CHECK (half_day IS NULL OR start_date = end_date),
    ADD CONSTRAINT leave_requests_days_check CHECK (days > 0),
    ADD CONSTRAINT leave_requests_attachment_check CHECK (
        (attachment_stored_filename IS NULL) = (attachment_original_filename IS NULL));

CREATE INDEX leave_requests_start_date_idx ON leave_requests(start_date);

-- LEAVE_VIEW / LEAVE_CREATE / LEAVE_APPROVE already exist (V22) and are granted there: admins get all three,
-- the principal view and approve, teachers view and create. The approval page reuses them.
