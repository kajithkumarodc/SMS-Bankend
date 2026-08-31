-- Attendance module -- first slice (plan section 2, "Attendance").
--
-- Tenant-scoped like students: carries `tenant_id` and follows the same
-- Row-Level Security pattern (ENABLE + FORCE RLS, one FOR ALL policy with
-- matching USING + WITH CHECK). With no tenant context set the policy matches
-- zero rows (fail-safe).
--
-- One record per student per day: UNIQUE(tenant_id, student_id, date). Re-marking
-- the same student+date is an update, not a new row (a teacher correcting a
-- same-day mistake) -- handled in the service as an upsert.

CREATE TABLE attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID NOT NULL REFERENCES students(id),
    date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- The user (teacher or admin) who marked it.
    marked_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attendance_records_tenant_student_date_key UNIQUE (tenant_id, student_id, date),
    CONSTRAINT attendance_records_status_check CHECK (status IN ('PRESENT', 'ABSENT', 'LATE'))
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4). The
-- unique constraint already indexes (tenant_id, student_id, date) -- serves the
-- per-student history query. Add a (tenant_id, date) index for the daily roster.
CREATE INDEX attendance_records_tenant_id_idx ON attendance_records(tenant_id);
CREATE INDEX attendance_records_tenant_date_idx ON attendance_records(tenant_id, date);

ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_records FORCE ROW LEVEL SECURITY;

CREATE POLICY attendance_records_current_tenant_policy ON attendance_records
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
