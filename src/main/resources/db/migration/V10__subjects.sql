-- Subjects -- minimal, just enough to support Exams (plan section 2,
-- "Academic management"). Not a full curriculum/syllabus system yet.
--
-- Both tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).

CREATE TABLE subjects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT subjects_tenant_school_name_key UNIQUE (tenant_id, school_id, name)
);

-- Which subjects a given class teaches: "Grade 5" can carry Math + Science +
-- English while "Grade 8" carries a different set.
CREATE TABLE class_subjects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    class_id UUID NOT NULL REFERENCES classes(id),
    subject_id UUID NOT NULL REFERENCES subjects(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT class_subjects_tenant_class_subject_key UNIQUE (tenant_id, class_id, subject_id)
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4). The
-- unique constraints already index (tenant_id, school_id, ...) / (tenant_id,
-- class_id, ...), so a plain tenant_id index is enough on top of those.
CREATE INDEX subjects_tenant_id_idx ON subjects(tenant_id);
CREATE INDEX class_subjects_tenant_id_idx ON class_subjects(tenant_id);

ALTER TABLE subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE subjects FORCE ROW LEVEL SECURITY;
ALTER TABLE class_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE class_subjects FORCE ROW LEVEL SECURITY;

CREATE POLICY subjects_current_tenant_policy ON subjects
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY class_subjects_current_tenant_policy ON class_subjects
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
