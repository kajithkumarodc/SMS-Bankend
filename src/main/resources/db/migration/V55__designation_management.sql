-- Human Resource -> Designation: managing the list of designations staff are assigned to (V44). The admin roles
-- get the permission; everyone who can see staff already reads the designations through the staff pages.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('DESIGNATION_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'DESIGNATION_MANAGE'
ON CONFLICT DO NOTHING;
