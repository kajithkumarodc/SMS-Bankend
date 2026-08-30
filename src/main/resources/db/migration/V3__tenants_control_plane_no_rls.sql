-- `tenants` is a control-plane table (see plan section 1): it holds the registry
-- of all tenants and must be readable before any tenant context is established --
-- for example to resolve a tenant from its identifier/subdomain during login.
-- Tenant-scoped RLS on this table makes that lookup impossible for a non-superuser
-- app role, so remove it here. Per-tenant tables (schools, users, roles, ...) keep
-- their RLS policies from V1/V2.
DROP POLICY IF EXISTS tenants_current_tenant_policy ON tenants;
ALTER TABLE tenants NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tenants DISABLE ROW LEVEL SECURITY;
