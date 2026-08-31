-- Audit trail (plan section 7.2: "every login, failed login, password change,
-- role change, and payment action should be logged ... an immutable audit trail").
--
-- Tenant-scoped and follows the existing RLS pattern, with one deliberate
-- difference: audit records are APPEND-ONLY for the application. The runtime role
-- may SELECT and INSERT its own tenant's rows but may never UPDATE or DELETE
-- them -- enforced two ways (RLS has no UPDATE/DELETE policy, and the grant is
-- revoked below).

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    -- Null for system / unattributable actions (e.g. a login attempt for an
    -- unknown user).
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40),
    entity_id UUID,
    -- Free-form context: old/new values, request metadata, ...
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- `tenant_id` leading everywhere; the admin "who did what" screen filters by
-- entity_type and date range.
CREATE INDEX audit_log_tenant_id_idx ON audit_log(tenant_id);
CREATE INDEX audit_log_tenant_created_at_idx ON audit_log(tenant_id, created_at DESC);
CREATE INDEX audit_log_tenant_entity_type_idx ON audit_log(tenant_id, entity_type);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

-- Only SELECT and INSERT policies -- with no UPDATE/DELETE policy, those commands
-- match zero rows under RLS.
CREATE POLICY audit_log_select_policy ON audit_log
    FOR SELECT USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY audit_log_insert_policy ON audit_log
    FOR INSERT WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

-- Defense in depth: hard-revoke the mutation grants the runtime role would
-- otherwise inherit from V4's ALTER DEFAULT PRIVILEGES.
REVOKE UPDATE, DELETE ON audit_log FROM ${app_user_name};
