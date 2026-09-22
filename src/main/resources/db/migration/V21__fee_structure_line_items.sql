-- Redesigns fee structures to match real school fee-structure sheets: fees
-- vary by class and academic year, broken into categories (Application,
-- Admission, Term I-IV) with an auto-computed total.
--
-- class_id NULL means "applies to all classes" (matches the app's existing
-- single flat-fee usage); set means class-specific, matching how a real
-- fee-structure sheet ties every column to one grade.
--
-- fee_structure_items is a pure breakdown/detail table: fee_structures.amount
-- remains the authoritative total (kept in sync with sum(items) whenever
-- items exist), so every existing reader of FeeStructure.amount / Invoice
-- (which snapshots that total at invoice-creation time) keeps working
-- unchanged. A structure with zero items is the backward-compatible "flat
-- amount" case explicitly called for -- no backfill of old rows needed.

ALTER TABLE fee_structures
    ADD COLUMN class_id UUID REFERENCES classes(id),
    ADD COLUMN academic_year VARCHAR(20);

-- fee_structures had no secondary index at all (the only one, on the now-gone
-- tenant_id, was dropped by V18's cascade). These support the class/year
-- filter and general school-scoped lookups.
CREATE INDEX fee_structures_school_id_idx ON fee_structures(school_id);
CREATE INDEX fee_structures_class_academic_year_idx ON fee_structures(class_id, academic_year);

CREATE TABLE fee_structure_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fee_structure_id UUID NOT NULL REFERENCES fee_structures(id) ON DELETE CASCADE,
    category VARCHAR(20) NOT NULL,
    label VARCHAR(150),
    amount NUMERIC(12, 2) NOT NULL,
    sequence_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fee_structure_items_amount_positive_check CHECK (amount > 0),
    CONSTRAINT fee_structure_items_category_check
        CHECK (category IN ('APPLICATION', 'ADMISSION', 'TERM_1', 'TERM_2', 'TERM_3', 'TERM_4', 'OTHER'))
);

CREATE INDEX fee_structure_items_fee_structure_id_idx ON fee_structure_items(fee_structure_id);

-- Bonus fix while in this area: invoices.student_id and .razorpay_order_id are
-- looked up on every "list a student's invoices" call and every Razorpay
-- webhook delivery respectively. Both indexes existed pre-V18 as
-- tenant-composite indexes and were silently dropped by that migration's
-- `DROP COLUMN tenant_id` cascade, with no plain replacement added at the
-- time -- both lookups have been full table scans since.
CREATE INDEX invoices_student_id_idx ON invoices(student_id);
CREATE INDEX invoices_razorpay_order_id_idx ON invoices(razorpay_order_id);
