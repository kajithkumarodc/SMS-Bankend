-- Human Resource -> Department: managing the list of departments staff are assigned to (V47). The admin roles
-- get the permission; everyone who can see staff already reads the departments through the staff pages.

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('DEPARTMENT_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'DEPARTMENT_MANAGE'
ON CONFLICT DO NOTHING;
