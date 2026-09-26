-- Setup Front Office: one page manages the four lookup lists the Front Office forms use --
-- Purpose (visitors, V32), Complaint Type (complaints, V36), Source (enquiries and complaints,
-- V23) and Reference (enquiries, V31). Each gains a description, and managing them gets its
-- own permission.

ALTER TABLE front_office_purposes ADD COLUMN description VARCHAR(500);
ALTER TABLE complaint_types ADD COLUMN description VARCHAR(500);
ALTER TABLE enquiry_sources ADD COLUMN description VARCHAR(500);
ALTER TABLE enquiry_references ADD COLUMN description VARCHAR(500);

-- Entries the school uses that earlier defaults didn't include. Existing names are left alone.
INSERT INTO front_office_purposes (id, name)
SELECT gen_random_uuid(), 'Curriculum Enrichment'
WHERE NOT EXISTS (SELECT 1 FROM front_office_purposes WHERE name = 'Curriculum Enrichment');

INSERT INTO enquiry_references (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('Lower Wing'), ('Partner School'), ('Self')) AS new_refs(name)
WHERE NOT EXISTS (SELECT 1 FROM enquiry_references r WHERE r.name = new_refs.name);

-- Managing the lists is an admin job; everyone who fills in the forms can still read them
-- through each module's own VIEW permission.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), 'FRONT_OFFICE_SETUP'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'FRONT_OFFICE_SETUP');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'FRONT_OFFICE_SETUP'
ON CONFLICT DO NOTHING;
