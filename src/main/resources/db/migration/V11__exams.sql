-- Examination & grading -- first slice (plan section 2). A working core:
-- schedule an exam for a class+subject, record marks per student. Question
-- banks, online exams, weighted grade schemes and transcripts come later.
--
-- Both tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).

CREATE TABLE exams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    class_id UUID NOT NULL REFERENCES classes(id),
    subject_id UUID NOT NULL REFERENCES subjects(id),
    name VARCHAR(150) NOT NULL,
    -- Exams are often scheduled ahead, so future dates are allowed (unlike attendance).
    exam_date DATE NOT NULL,
    max_marks NUMERIC(6, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exam_marks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    exam_id UUID NOT NULL REFERENCES exams(id),
    student_id UUID NOT NULL REFERENCES students(id),
    marks_obtained NUMERIC(6, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- One mark entry per student per exam; re-recording is an update (a teacher
    -- correcting a mistake), not a duplicate error -- handled in the service.
    CONSTRAINT exam_marks_tenant_exam_student_key UNIQUE (tenant_id, exam_id, student_id)
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX exams_tenant_id_idx ON exams(tenant_id);
CREATE INDEX exams_tenant_class_id_idx ON exams(tenant_id, class_id);
CREATE INDEX exam_marks_tenant_id_idx ON exam_marks(tenant_id);
CREATE INDEX exam_marks_tenant_student_id_idx ON exam_marks(tenant_id, student_id);

ALTER TABLE exams ENABLE ROW LEVEL SECURITY;
ALTER TABLE exams FORCE ROW LEVEL SECURITY;
ALTER TABLE exam_marks ENABLE ROW LEVEL SECURITY;
ALTER TABLE exam_marks FORCE ROW LEVEL SECURITY;

CREATE POLICY exams_current_tenant_policy ON exams
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY exam_marks_current_tenant_policy ON exam_marks
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
