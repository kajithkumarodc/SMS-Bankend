-- In-app notifications, and the permissions the leave approval flow needs.
--
-- Leave approval flow (Human Resource > Approve Leave Request):
--   * a Teacher / Librarian / other staff request goes to the Principal;
--   * a Principal request goes to the Super Admin;
--   * a Super Admin or School Admin can approve any request except their own.
-- Each approver is notified when a request is sent, and the applicant is notified of the decision.

-- --- 1. Notifications ------------------------------------------------------------------------------

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- What it is about, e.g. LEAVE_REQUESTED / LEAVE_DECIDED; the page uses it for the icon.
    type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    -- App route to open when the notification is clicked.
    link VARCHAR(300),
    entity_id UUID,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX notifications_recipient_idx ON notifications(recipient_user_id, created_at DESC);
CREATE INDEX notifications_unread_idx ON notifications(recipient_user_id) WHERE read_at IS NULL;

-- --- 2. Who can send a leave request ---------------------------------------------------------------
-- V22 gave leave view/create only to teachers. The Principal now applies for their own leave (to the Super
-- Admin), and the other staff roles apply for theirs (to the Principal). LEAVE_APPROVE stays with the admin
-- roles and the Principal; which requests each may decide is a role rule in the leave service.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('LEAVE_VIEW', 'LEAVE_CREATE')
WHERE r.name IN ('PRINCIPAL', 'LIBRARIAN', 'ACCOUNTANT', 'RECEPTIONIST')
ON CONFLICT DO NOTHING;
