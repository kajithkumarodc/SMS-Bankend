DROP POLICY tenants_current_tenant_policy ON tenants;
DROP POLICY schools_current_tenant_policy ON schools;
DROP POLICY users_current_tenant_policy ON users;
DROP POLICY roles_current_tenant_policy ON roles;
DROP POLICY permissions_current_tenant_policy ON permissions;
DROP POLICY user_roles_current_tenant_policy ON user_roles;

CREATE POLICY tenants_current_tenant_policy ON tenants
    FOR ALL USING (id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY schools_current_tenant_policy ON schools
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY users_current_tenant_policy ON users
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY roles_current_tenant_policy ON roles
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY permissions_current_tenant_policy ON permissions
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY user_roles_current_tenant_policy ON user_roles
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));