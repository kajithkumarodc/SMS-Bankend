-- Staff Management + HR & payroll -- first slice (plan section 2, "Staff
-- Management: staff records/profiles, department/designation assignment, ...
-- leave management ... feeds into HR & payroll" + "HR & payroll: payroll
-- processing, salary structures, ..."). This slice is staff profiles + leave
-- requests + a minimal payroll core (generate a monthly record, no statutory
-- deduction schedules / salary-structure templates yet -- those are later).
--
-- All three new tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).
-- With no tenant context set the policy matches zero rows (fail-safe).

CREATE TABLE staff_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    -- The user account this profile belongs to (typically SCHOOL_ADMIN or
    -- TEACHER). One profile per user per tenant.
    user_id UUID NOT NULL REFERENCES users(id),
    employee_code VARCHAR(50) NOT NULL,
    department VARCHAR(200),
    designation VARCHAR(200),
    date_of_joining DATE NOT NULL,
    salary_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT staff_profiles_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT staff_profiles_salary_amount_check CHECK (salary_amount >= 0),
    CONSTRAINT staff_profiles_tenant_user_key UNIQUE (tenant_id, user_id),
    -- Mirrors students.admission_number: a tenant-unique business key.
    CONSTRAINT staff_profiles_tenant_employee_code_key UNIQUE (tenant_id, employee_code)
);

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    -- References users directly (not staff_profiles) -- the requester's identity
    -- is the user account; a leave request always resolves back to a staff
    -- profile via (tenant_id, user_id) when one is needed.
    staff_user_id UUID NOT NULL REFERENCES users(id),
    leave_type VARCHAR(50) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT leave_requests_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT leave_requests_date_range_check CHECK (end_date >= start_date)
);

CREATE TABLE payroll_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    staff_user_id UUID NOT NULL REFERENCES users(id),
    month INTEGER NOT NULL,
    year INTEGER NOT NULL,
    base_salary NUMERIC(12, 2) NOT NULL,
    deductions NUMERIC(12, 2) NOT NULL DEFAULT 0,
    net_pay NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMPTZ,
    CONSTRAINT payroll_records_month_check CHECK (month BETWEEN 1 AND 12),
    CONSTRAINT payroll_records_year_check CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT payroll_records_deductions_check CHECK (deductions >= 0),
    CONSTRAINT payroll_records_net_pay_check CHECK (net_pay = base_salary - deductions),
    CONSTRAINT payroll_records_status_check CHECK (status IN ('PENDING', 'PAID')),
    -- One payroll record per staff member per month/year -- a second attempt
    -- must be a clean 409, never a silent duplicate.
    CONSTRAINT payroll_records_tenant_staff_month_year_key UNIQUE (tenant_id, staff_user_id, month, year)
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX staff_profiles_tenant_id_idx ON staff_profiles(tenant_id);
CREATE INDEX staff_profiles_tenant_user_id_idx ON staff_profiles(tenant_id, user_id);
CREATE INDEX leave_requests_tenant_id_idx ON leave_requests(tenant_id);
CREATE INDEX leave_requests_tenant_staff_user_id_idx ON leave_requests(tenant_id, staff_user_id);
CREATE INDEX payroll_records_tenant_id_idx ON payroll_records(tenant_id);
CREATE INDEX payroll_records_tenant_staff_user_id_idx ON payroll_records(tenant_id, staff_user_id);

ALTER TABLE staff_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE payroll_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_records FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_profiles_current_tenant_policy ON staff_profiles
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY leave_requests_current_tenant_policy ON leave_requests
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY payroll_records_current_tenant_policy ON payroll_records
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
