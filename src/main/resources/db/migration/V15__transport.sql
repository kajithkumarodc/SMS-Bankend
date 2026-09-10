-- Transport -- first slice (plan section 2, "Transport: routes, stops, vehicles,
-- driver assignment, student-route mapping ..."). This slice is routes +
-- vehicles + student-route assignment only; stops, fee integration and
-- GPS/live-tracking are later considerations noted in the plan.
--
-- Both new tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).
-- With no tenant context set the policy matches zero rows (fail-safe).

CREATE TABLE transport_routes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transport_vehicles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    -- A vehicle may be unassigned (in the depot, not yet on a route).
    route_id UUID REFERENCES transport_routes(id),
    registration_number VARCHAR(30) NOT NULL,
    driver_name VARCHAR(200) NOT NULL,
    driver_contact VARCHAR(50),
    capacity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT transport_vehicles_capacity_positive_check CHECK (capacity > 0),
    CONSTRAINT transport_vehicles_tenant_registration_key UNIQUE (tenant_id, registration_number)
);

-- A student may optionally use a transport route. Nullable; `students` already
-- carries tenant RLS from V5, so no policy change is needed here.
ALTER TABLE students ADD COLUMN transport_route_id UUID REFERENCES transport_routes(id);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX transport_routes_tenant_id_idx ON transport_routes(tenant_id);
CREATE INDEX transport_vehicles_tenant_id_idx ON transport_vehicles(tenant_id);
CREATE INDEX transport_vehicles_tenant_route_id_idx ON transport_vehicles(tenant_id, route_id);
CREATE INDEX students_tenant_transport_route_id_idx ON students(tenant_id, transport_route_id);

ALTER TABLE transport_routes ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_routes FORCE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicles FORCE ROW LEVEL SECURITY;

CREATE POLICY transport_routes_current_tenant_policy ON transport_routes
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY transport_vehicles_current_tenant_policy ON transport_vehicles
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
