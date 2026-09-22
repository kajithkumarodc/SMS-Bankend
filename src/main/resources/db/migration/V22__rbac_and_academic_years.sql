-- RBAC Phase 1 (database-driven roles/permissions) + Academic Years.
--
-- Roles and permissions already exist as plain lookup tables (post-V18
-- single-tenant). This adds the missing join table between them, seeds a
-- permission catalog + starter role grants, and adds the fields needed for
-- local (no-email-required) user management: forced password reset.
--
-- Existing @PreAuthorize(Roles.HAS_*) role checks (61 call sites across the
-- app) are left completely untouched -- this is an additive authorization
-- layer. New Users/Roles/Permissions/Academic-Years endpoints use it; other
-- modules keep their existing role-based checks unless a later phase
-- migrates them.

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX role_permissions_permission_id_idx ON role_permissions(permission_id);

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- New roles beyond the original SCHOOL_ADMIN/TEACHER/STUDENT/PARENT.
-- SCHOOL_ADMIN is left as-is -- SUPER_ADMIN sits above it, not a replacement.
INSERT INTO roles (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('SUPER_ADMIN'), ('PRINCIPAL'), ('ACCOUNTANT'), ('LIBRARIAN'), ('RECEPTIONIST')
) AS new_roles(name)
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE roles.name = new_roles.name);

-- Permission catalog: <MODULE>_<ACTION>. Only meaningful combinations are
-- seeded (e.g. no APPROVE on STUDENT). Admins can add more later from the
-- Permission management screen.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('STUDENT_VIEW'), ('STUDENT_CREATE'), ('STUDENT_EDIT'), ('STUDENT_DELETE'), ('STUDENT_EXPORT'), ('STUDENT_PRINT'),
    ('ATTENDANCE_VIEW'), ('ATTENDANCE_CREATE'), ('ATTENDANCE_EDIT'), ('ATTENDANCE_EXPORT'),
    ('EXAM_VIEW'), ('EXAM_CREATE'), ('EXAM_EDIT'), ('EXAM_DELETE'), ('EXAM_EXPORT'), ('EXAM_PRINT'),
    ('ACADEMICS_VIEW'), ('ACADEMICS_CREATE'), ('ACADEMICS_EDIT'), ('ACADEMICS_DELETE'),
    ('FEE_VIEW'), ('FEE_CREATE'), ('FEE_EDIT'), ('FEE_DELETE'), ('FEE_APPROVE'), ('FEE_EXPORT'), ('FEE_PRINT'),
    ('LIBRARY_VIEW'), ('LIBRARY_CREATE'), ('LIBRARY_EDIT'), ('LIBRARY_DELETE'), ('LIBRARY_EXPORT'),
    ('TRANSPORT_VIEW'), ('TRANSPORT_CREATE'), ('TRANSPORT_EDIT'), ('TRANSPORT_DELETE'),
    ('HOSTEL_VIEW'), ('HOSTEL_CREATE'), ('HOSTEL_EDIT'), ('HOSTEL_DELETE'),
    ('STAFF_VIEW'), ('STAFF_CREATE'), ('STAFF_EDIT'), ('STAFF_DELETE'), ('STAFF_EXPORT'),
    ('LEAVE_VIEW'), ('LEAVE_CREATE'), ('LEAVE_APPROVE'),
    ('PAYROLL_VIEW'), ('PAYROLL_CREATE'), ('PAYROLL_APPROVE'), ('PAYROLL_EXPORT'), ('PAYROLL_PRINT'),
    ('ANNOUNCEMENT_VIEW'), ('ANNOUNCEMENT_CREATE'), ('ANNOUNCEMENT_DELETE'),
    ('REPORT_VIEW'), ('REPORT_EXPORT'), ('REPORT_PRINT'),
    ('AUDIT_VIEW'),
    ('USER_VIEW'), ('USER_CREATE'), ('USER_EDIT'), ('USER_DELETE'),
    ('ROLE_VIEW'), ('ROLE_CREATE'), ('ROLE_EDIT'), ('ROLE_DELETE'),
    ('ACADEMIC_YEAR_VIEW'), ('ACADEMIC_YEAR_CREATE'), ('ACADEMIC_YEAR_EDIT'), ('ACADEMIC_YEAR_APPROVE')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

-- Default role -> permission grants. SUPER_ADMIN and SCHOOL_ADMIN get
-- everything; other roles get a sensible starting scope an admin can adjust
-- at any time from the Roles screen (this is data, not code).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'STUDENT_VIEW', 'ATTENDANCE_VIEW', 'ATTENDANCE_CREATE', 'ATTENDANCE_EDIT',
    'EXAM_VIEW', 'EXAM_CREATE', 'EXAM_EDIT', 'ACADEMICS_VIEW',
    'LEAVE_VIEW', 'LEAVE_CREATE', 'ANNOUNCEMENT_VIEW'
)
WHERE r.name = 'TEACHER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'FEE_VIEW', 'FEE_CREATE', 'FEE_EDIT', 'FEE_APPROVE', 'FEE_EXPORT', 'FEE_PRINT',
    'STUDENT_VIEW', 'PAYROLL_VIEW', 'PAYROLL_CREATE', 'PAYROLL_EXPORT', 'PAYROLL_PRINT',
    'REPORT_VIEW', 'REPORT_EXPORT', 'REPORT_PRINT'
)
WHERE r.name = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'LIBRARY_VIEW', 'LIBRARY_CREATE', 'LIBRARY_EDIT', 'LIBRARY_DELETE', 'LIBRARY_EXPORT', 'STUDENT_VIEW'
)
WHERE r.name = 'LIBRARIAN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'STUDENT_VIEW', 'STUDENT_CREATE', 'ANNOUNCEMENT_VIEW'
)
WHERE r.name = 'RECEPTIONIST'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'STUDENT_VIEW', 'STUDENT_EXPORT', 'ATTENDANCE_VIEW', 'EXAM_VIEW', 'EXAM_EXPORT',
    'FEE_VIEW', 'STAFF_VIEW', 'LEAVE_VIEW', 'LEAVE_APPROVE', 'PAYROLL_VIEW', 'PAYROLL_APPROVE',
    'REPORT_VIEW', 'REPORT_EXPORT', 'REPORT_PRINT', 'ANNOUNCEMENT_VIEW', 'ANNOUNCEMENT_CREATE',
    'ACADEMIC_YEAR_VIEW', 'ACADEMIC_YEAR_APPROVE'
)
WHERE r.name = 'PRINCIPAL'
ON CONFLICT DO NOTHING;

-- Academic sessions/years -- "Academic Session -> Class -> Section ->
-- Subject -> Teacher -> Students" plus student promotion between sessions.
CREATE TABLE academic_years (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT academic_years_name_key UNIQUE (name),
    CONSTRAINT academic_years_date_range_check CHECK (end_date > start_date)
);

-- At most one academic year may be "current" at a time.
CREATE UNIQUE INDEX academic_years_single_current_idx ON academic_years(is_current) WHERE is_current;
