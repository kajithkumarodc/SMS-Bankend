-- Communication -- announcements (plan section 2, "Communication:
-- announcements, notifications, ..."). A first slice: school-wide messages that
-- everyone in the tenant sees (admin, teacher, student, parent). Notifications,
-- messaging, events and circulars come later.
--
-- Tenant-scoped and follows the existing RLS pattern (ENABLE + FORCE RLS, one
-- FOR ALL policy with matching USING + WITH CHECK). With no tenant context set
-- the policy matches zero rows (fail-safe).

CREATE TABLE announcements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    -- The SCHOOL_ADMIN who posted it.
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4). The
-- list and the dashboard both read newest-first, so index (tenant_id, created_at DESC).
CREATE INDEX announcements_tenant_id_idx ON announcements(tenant_id);
CREATE INDEX announcements_tenant_created_at_idx ON announcements(tenant_id, created_at DESC);

ALTER TABLE announcements ENABLE ROW LEVEL SECURITY;
ALTER TABLE announcements FORCE ROW LEVEL SECURITY;

CREATE POLICY announcements_current_tenant_policy ON announcements
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
