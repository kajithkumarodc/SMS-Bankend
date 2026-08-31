-- Student Information System (SIS) -- first slice (plan section 2).
--
-- `students` is a tenant-scoped table: it carries `tenant_id` and follows the
-- same Row-Level Security pattern as schools/users/roles (V1/V2) -- ENABLE +
-- FORCE RLS, and a single FOR ALL policy with matching USING + WITH CHECK so a
-- session can neither read nor write rows outside its `app.current_tenant_id`.
-- With no tenant context set, the policy matches zero rows (fail-safe).

CREATE TABLE students (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    full_name VARCHAR(200) NOT NULL,
    date_of_birth DATE,
    admission_number VARCHAR(60) NOT NULL,
    guardian_name VARCHAR(200),
    guardian_contact VARCHAR(50),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Admission numbers are unique per tenant, not globally (plan section 4).
    CONSTRAINT students_tenant_admission_number_key UNIQUE (tenant_id, admission_number),
    CONSTRAINT students_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4): keeps
-- RLS-filtered scans fast. The composite covers the common "list this school's
-- students within the tenant" access path.
CREATE INDEX students_tenant_id_idx ON students(tenant_id);
CREATE INDEX students_tenant_school_id_idx ON students(tenant_id, school_id);

ALTER TABLE students ENABLE ROW LEVEL SECURITY;
ALTER TABLE students FORCE ROW LEVEL SECURITY;

CREATE POLICY students_current_tenant_policy ON students
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
