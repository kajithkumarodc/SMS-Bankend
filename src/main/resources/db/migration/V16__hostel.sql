-- Hostel / dormitory -- first slice (plan section 2, "Hostel/dormitory:
-- blocks/rooms, allocation, hostel attendance, mess/fee management"). This slice
-- is blocks + rooms + student allocation only; hostel attendance and mess/fee
-- integration are later. A future hostel fee can reuse `fee_structures`.
--
-- Both new tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).
-- With no tenant context set the policy matches zero rows (fail-safe).

CREATE TABLE hostel_blocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE hostel_rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    block_id UUID NOT NULL REFERENCES hostel_blocks(id),
    room_number VARCHAR(30) NOT NULL,
    capacity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT hostel_rooms_capacity_positive_check CHECK (capacity > 0),
    -- Room numbers repeat across blocks ("A-101" in Block A, "A-101" in Block B),
    -- so uniqueness is per (tenant, block).
    CONSTRAINT hostel_rooms_tenant_block_number_key UNIQUE (tenant_id, block_id, room_number)
);

-- A student may optionally be allocated a hostel room. Nullable; `students`
-- already carries tenant RLS from V5, so no policy change is needed here.
ALTER TABLE students ADD COLUMN hostel_room_id UUID REFERENCES hostel_rooms(id);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX hostel_blocks_tenant_id_idx ON hostel_blocks(tenant_id);
CREATE INDEX hostel_rooms_tenant_id_idx ON hostel_rooms(tenant_id);
CREATE INDEX hostel_rooms_tenant_block_id_idx ON hostel_rooms(tenant_id, block_id);
CREATE INDEX students_tenant_hostel_room_id_idx ON students(tenant_id, hostel_room_id);

ALTER TABLE hostel_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE hostel_blocks FORCE ROW LEVEL SECURITY;
ALTER TABLE hostel_rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE hostel_rooms FORCE ROW LEVEL SECURITY;

CREATE POLICY hostel_blocks_current_tenant_policy ON hostel_blocks
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY hostel_rooms_current_tenant_policy ON hostel_rooms
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
