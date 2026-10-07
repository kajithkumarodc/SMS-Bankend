-- Human Resource -> Leave Type: managing the list of leave types (V47). The admin roles get the permission;
-- everyone who can see leave requests already reads the active types through the leave pages.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('LEAVE_TYPE_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'LEAVE_TYPE_MANAGE'
ON CONFLICT DO NOTHING;
