-- A record of every change to what one student owes that did not come from a payment:
--   ADMISSION_ADJUSTMENT -- the admission form changed the fee-template amount for this student;
--   STRUCTURE_CHANGE     -- Fees Master edited a fee structure and the school explicitly chose to apply
--                           the change to bills already raised (future bills get it automatically).
CREATE TABLE fee_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('ADMISSION_ADJUSTMENT', 'STRUCTURE_CHANGE')),
    old_amount NUMERIC(12, 2) NOT NULL,
    new_amount NUMERIC(12, 2) NOT NULL,
    reason VARCHAR(500),
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX fee_adjustments_invoice_idx ON fee_adjustments (invoice_id);
