-- Phase 5: Fees, Discounts, Fee Collection & Accounting. Extends the existing
-- fee_structures/fee_structure_items/invoices model (V12/V21) rather than
-- duplicating it -- invoices remain "one student's bill for one FeeStructure",
-- now able to carry a discount and be settled by one or more payments instead
-- of a single all-or-nothing Razorpay webhook. fee_payments is the single,
-- append-only ledger every payment (manual or online) and every reversal
-- flows through, so collected-income figures are never double-counted and a
-- transaction is never physically deleted.

-- 1) Fee types: a configurable catalog (Tuition/Admission/Examination/...),
-- not a hardcoded enum -- same "lookup table, not a fixed set" precedent as
-- enquiry_sources (V23). Optional tag on a fee structure item, used for
-- reporting/filtering; the existing category breakdown (APPLICATION/ADMISSION/
-- TERM_1-4/OTHER, V21) is a different, orthogonal concept -- which billing
-- period a line belongs to -- and is left untouched.
CREATE TABLE fee_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fee_types_name_key UNIQUE (name)
);

INSERT INTO fee_types (id, name) VALUES
    (gen_random_uuid(), 'Tuition Fee'),
    (gen_random_uuid(), 'Admission Fee'),
    (gen_random_uuid(), 'Examination Fee'),
    (gen_random_uuid(), 'Transport Fee'),
    (gen_random_uuid(), 'Library Fee'),
    (gen_random_uuid(), 'Hostel Fee'),
    (gen_random_uuid(), 'Activity Fee'),
    (gen_random_uuid(), 'Other');

ALTER TABLE fee_structure_items ADD COLUMN fee_type_id UUID REFERENCES fee_types(id);
CREATE INDEX fee_structure_items_fee_type_id_idx ON fee_structure_items(fee_type_id);

-- 2) Fee structure: frequency, optional late fee, and an active/inactive
-- status an admin can toggle without deleting a structure invoices already
-- reference.
ALTER TABLE fee_structures
    ADD COLUMN frequency VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME',
    ADD COLUMN late_fee_amount NUMERIC(12, 2),
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE fee_structures ADD CONSTRAINT fee_structures_frequency_check
    CHECK (frequency IN ('ONE_TIME', 'MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL'));
ALTER TABLE fee_structures ADD CONSTRAINT fee_structures_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE fee_structures ADD CONSTRAINT fee_structures_late_fee_amount_check
    CHECK (late_fee_amount IS NULL OR late_fee_amount > 0);

-- 3) Discounts: a reusable, admin-configurable catalog (e.g. "Sibling
-- Discount 10%", "Staff Ward Waiver 500"). Applying one to an invoice is a
-- deliberate staff action (FeeCollectionService.applyDiscount), not an
-- auto-matching rules engine -- the invoice snapshots the computed amount
-- (discount_amount below), same "snapshot at the time, don't recompute from a
-- live join" precedent as invoices.amount itself.
CREATE TABLE fee_discounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    value NUMERIC(12, 2) NOT NULL,
    -- Optional: restricts this discount to invoices raised against one fee
    -- structure ("applicable fee item/group"); null = usable on any invoice.
    fee_structure_id UUID REFERENCES fee_structures(id),
    valid_from DATE,
    valid_to DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fee_discounts_type_check CHECK (discount_type IN ('FIXED', 'PERCENTAGE')),
    CONSTRAINT fee_discounts_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fee_discounts_value_positive_check CHECK (value > 0),
    CONSTRAINT fee_discounts_percentage_range_check
        CHECK (discount_type <> 'PERCENTAGE' OR value <= 100),
    CONSTRAINT fee_discounts_validity_range_check
        CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX fee_discounts_fee_structure_id_idx ON fee_discounts(fee_structure_id);

-- 4) Invoice extensions: net_amount is the authoritative payable figure
-- (amount - discount_amount [+ late_fee_amount once applied]) every payment
-- is validated against; paid_amount is maintained transactionally alongside
-- every fee_payments insert/reversal in the same service method, so it is
-- never a separate source of truth that can drift. Backfill: every existing
-- invoice has no discount, so net_amount = amount; a PAID one already
-- collected its full amount.
ALTER TABLE invoices
    ADD COLUMN discount_id UUID REFERENCES fee_discounts(id),
    ADD COLUMN discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN net_amount NUMERIC(12, 2),
    ADD COLUMN late_fee_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN late_fee_applied BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN paid_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN assigned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

UPDATE invoices SET net_amount = amount;
UPDATE invoices SET paid_amount = amount WHERE status = 'PAID';
ALTER TABLE invoices ALTER COLUMN net_amount SET NOT NULL;

ALTER TABLE invoices ADD CONSTRAINT invoices_discount_amount_check CHECK (discount_amount >= 0);
ALTER TABLE invoices ADD CONSTRAINT invoices_net_amount_check CHECK (net_amount >= 0);
ALTER TABLE invoices ADD CONSTRAINT invoices_paid_amount_check CHECK (paid_amount >= 0);
ALTER TABLE invoices ADD CONSTRAINT invoices_discount_not_exceeding_amount_check
    CHECK (discount_amount <= amount);

-- PARTIALLY_PAID joins the existing PENDING/PAID/FAILED set (V12). OVERDUE is
-- deliberately NOT a stored status -- it is (due_date passed AND balance > 0),
-- computed at query time from real data, exactly as the plan requires,
-- avoiding a background job that could fall out of sync.
ALTER TABLE invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check
    CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'PAID', 'FAILED'));

-- 5) Payments: the single ledger every collection (CASH/BANK_TRANSFER/CHEQUE/
-- OTHER/ONLINE) and every reversal flows through -- manual collection in this
-- phase, and the existing Razorpay webhook/dev-simulate path now records a
-- ledger row here too instead of only flipping invoices.status, so "income" is
-- never computed two different ways. A reversal is its own new row (type
-- REVERSAL, reverses_payment_id pointing at the original PAYMENT row) --
-- history is never edited or deleted (plan section on refunds: "never
-- silently delete a financial transaction").
CREATE TABLE fee_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    type VARCHAR(20) NOT NULL DEFAULT 'PAYMENT',
    amount NUMERIC(12, 2) NOT NULL,
    method VARCHAR(20) NOT NULL,
    reference_number VARCHAR(255),
    receipt_number VARCHAR(30) NOT NULL,
    collected_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    notes VARCHAR(500),
    -- Set only on a REVERSAL row: which PAYMENT row it reverses, and why.
    reverses_payment_id UUID REFERENCES fee_payments(id),
    reason VARCHAR(500),
    paid_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fee_payments_amount_positive_check CHECK (amount > 0),
    CONSTRAINT fee_payments_type_check CHECK (type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT fee_payments_method_check
        CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CHEQUE', 'ONLINE', 'OTHER')),
    CONSTRAINT fee_payments_receipt_number_key UNIQUE (receipt_number),
    CONSTRAINT fee_payments_reversal_reference_check
        CHECK (type = 'PAYMENT' OR (reverses_payment_id IS NOT NULL AND reason IS NOT NULL))
);

CREATE INDEX fee_payments_invoice_id_idx ON fee_payments(invoice_id);
CREATE INDEX fee_payments_paid_at_idx ON fee_payments(paid_at);
CREATE INDEX fee_payments_collected_by_user_id_idx ON fee_payments(collected_by_user_id);
CREATE INDEX fee_payments_reverses_payment_id_idx ON fee_payments(reverses_payment_id);

-- Human-friendly, collision-proof receipt numbers under concurrent collection
-- (a count(*)+1 scheme would race). The V23 fix already extends
-- ALTER DEFAULT PRIVILEGES to cover future sequences, so no separate GRANT is
-- needed here for the restricted runtime role.
CREATE SEQUENCE fee_receipt_number_seq START WITH 1 INCREMENT BY 1;

-- Existing PAID invoices (Razorpay only, pre-Phase-5) get one synthetic
-- PAYMENT row each so payment history / receipts / income reports have a
-- ledger entry for money already collected, instead of a silent gap.
INSERT INTO fee_payments (id, invoice_id, type, amount, method, reference_number, receipt_number, paid_at)
SELECT gen_random_uuid(), id, 'PAYMENT', amount, 'ONLINE', razorpay_payment_id,
       'RCPT-' || LPAD(nextval('fee_receipt_number_seq')::text, 6, '0'), COALESCE(paid_at, created_at)
FROM invoices WHERE status = 'PAID';

-- 6) New permissions this phase needs, following the existing FEE_* catalog
-- (V22) -- FEE_VIEW/CREATE/EDIT/DELETE/APPROVE/EXPORT/PRINT already exist and
-- are reused unchanged for structures/invoices/reports/receipt-view.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES
    ('FEE_ASSIGN'), ('FEE_COLLECT'), ('FEE_DISCOUNT'), ('FEE_REFUND'), ('FEE_RECEIPT_PRINT')
) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

-- SUPER_ADMIN/SCHOOL_ADMIN: re-run the V22 "everything" grant so these new
-- rows reach them too (that CROSS JOIN only ran once, at V22's time).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN')
  AND p.name IN ('FEE_ASSIGN', 'FEE_COLLECT', 'FEE_DISCOUNT', 'FEE_REFUND', 'FEE_RECEIPT_PRINT')
ON CONFLICT DO NOTHING;

-- ACCOUNTANT already has FEE_VIEW/CREATE/EDIT/APPROVE/EXPORT/PRINT (V22) --
-- extend with the new collection-workflow permissions; refunds stay
-- SUPER_ADMIN/SCHOOL_ADMIN-only by default (an admin can grant FEE_REFUND to
-- ACCOUNTANT later from the Roles screen if a school wants that).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN (
    'FEE_ASSIGN', 'FEE_COLLECT', 'FEE_DISCOUNT', 'FEE_RECEIPT_PRINT'
)
WHERE r.name = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;
