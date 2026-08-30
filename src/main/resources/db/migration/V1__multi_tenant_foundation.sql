CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    identifier VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE schools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT users_tenant_email_key UNIQUE (tenant_id, email)
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(50) NOT NULL,
    CONSTRAINT roles_tenant_name_key UNIQUE (tenant_id, name)
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(100) NOT NULL,
    CONSTRAINT permissions_tenant_name_key UNIQUE (tenant_id, name)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT user_roles_tenant_consistency CHECK (tenant_id IS NOT NULL)
);

CREATE INDEX schools_tenant_id_idx ON schools(tenant_id);
CREATE INDEX users_tenant_id_idx ON users(tenant_id);
CREATE INDEX roles_tenant_id_idx ON roles(tenant_id);
CREATE INDEX permissions_tenant_id_idx ON permissions(tenant_id);
CREATE INDEX user_roles_tenant_id_idx ON user_roles(tenant_id);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE schools ENABLE ROW LEVEL SECURITY;
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
ALTER TABLE schools FORCE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE permissions FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_current_tenant_policy ON tenants
    USING (id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY schools_current_tenant_policy ON schools
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY users_current_tenant_policy ON users
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY roles_current_tenant_policy ON roles
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY permissions_current_tenant_policy ON permissions
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY user_roles_current_tenant_policy ON user_roles
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));
