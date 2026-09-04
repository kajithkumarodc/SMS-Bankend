-- Financial Management (Fees) -- first slice (plan section 2,
-- "Financial Management (Fees)"). A working core: a school admin defines a fee
-- structure (a named amount with a due date) and generates a per-student
-- invoice against it; the invoice is then paid online through Razorpay, whose
-- server-to-server webhook (signature-verified -- plan section 7.2a/f) flips the
-- invoice to PAID. Discounts, partial payments, installments, receipts, refunds
-- and reconciliation come later.
--
-- Both tables are tenant-scoped and follow the existing RLS pattern
-- (ENABLE + FORCE RLS, one FOR ALL policy with matching USING + WITH CHECK).
-- With no tenant context set the policy matches zero rows (fail-safe).

CREATE TABLE fee_structures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    school_id UUID NOT NULL REFERENCES schools(id),
    name VARCHAR(150) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    due_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fee_structures_amount_positive_check CHECK (amount > 0)
);

CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID NOT NULL REFERENCES students(id),
    fee_structure_id UUID NOT NULL REFERENCES fee_structures(id),
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Razorpay references. Null until a checkout Order is created / the payment
    -- webhook confirms it. We never store card/bank details -- only these safe
    -- gateway references (plan section 7.2a, "tokenization").
    razorpay_order_id VARCHAR(255),
    razorpay_payment_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMPTZ,
    CONSTRAINT invoices_amount_positive_check CHECK (amount > 0),
    CONSTRAINT invoices_status_check CHECK (status IN ('PENDING', 'PAID', 'FAILED'))
);

-- `tenant_id` as the leading index column everywhere (plan section 1/4).
CREATE INDEX fee_structures_tenant_id_idx ON fee_structures(tenant_id);
CREATE INDEX invoices_tenant_id_idx ON invoices(tenant_id);
CREATE INDEX invoices_tenant_student_id_idx ON invoices(tenant_id, student_id);
-- The webhook resolves an invoice from the Razorpay order id (still inside a
-- tenant context, so the lookup stays tenant-filtered).
CREATE INDEX invoices_tenant_razorpay_order_id_idx ON invoices(tenant_id, razorpay_order_id);

ALTER TABLE fee_structures ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_structures FORCE ROW LEVEL SECURITY;
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;

CREATE POLICY fee_structures_current_tenant_policy ON fee_structures
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
CREATE POLICY invoices_current_tenant_policy ON invoices
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));
