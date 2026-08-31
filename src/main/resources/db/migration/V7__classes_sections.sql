-- Classes / Sections -- minimal grouping for students (plan section 2,
-- "Academic management"). Just enough to group students for attendance and
-- future features; full curriculum/timetable comes later.
--
-- Both tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).

CREATE TABLE classes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT classes_tenant_school_name_key UNIQUE (tenant_id, school_id, name)
);

CREATE TABLE sections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    class_id UUID NOT NULL REFERENCES classes(id),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sections_tenant_class_name_key UNIQUE (tenant_id, class_id, name)
);

-- A student may not be assigned to a section yet -> nullable.
ALTER TABLE students ADD COLUMN section_id UUID REFERENCES sections(id);

-- `tenant_id` as the leading index column everywhere (plan section 1/4). The
-- unique constraints already index (tenant_id, school_id, ...) / (tenant_id,
-- class_id, ...), so a plain tenant_id index is enough on top of those.
CREATE INDEX classes_tenant_id_idx ON classes(tenant_id);
CREATE INDEX sections_tenant_id_idx ON sections(tenant_id);
CREATE INDEX students_tenant_section_id_idx ON students(tenant_id, section_id);

ALTER TABLE classes ENABLE ROW LEVEL SECURITY;
ALTER TABLE classes FORCE ROW LEVEL SECURITY;
ALTER TABLE sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE sections FORCE ROW LEVEL SECURITY;

CREATE POLICY classes_current_tenant_policy ON classes
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY sections_current_tenant_policy ON sections
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
