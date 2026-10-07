-- Line-by-line fee collection (Smart School "Student Fees"): a bill is made of fee lines (fee type x term, each
-- with its own due date), and a collection says how much of each line -- and how much fine -- it pays.
--
-- Totals stay on invoices (amount, discount, late fee, net, paid) exactly as before, so every existing report,
-- receipt and the Razorpay flow keep working. Lines and allocations only break those totals down:
--   * invoice_lines.amount sums to invoices.amount; discount_amount sums to invoices.discount_amount;
--   * a payment's allocations say which lines it paid (amount) and the fine it collected on each;
--   * payments without allocations (older ones, online ones) are spread over the lines oldest-due-first when read.

CREATE TABLE invoice_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    label VARCHAR(150) NOT NULL,
    fee_type_id UUID REFERENCES fee_types (id),
    category VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    due_date DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    sequence_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX invoice_lines_invoice_idx ON invoice_lines (invoice_id);

CREATE TABLE fee_payment_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES fee_payments (id),
    invoice_line_id UUID NOT NULL REFERENCES invoice_lines (id) ON DELETE CASCADE,
    amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    fine_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (fine_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (amount + fine_amount > 0)
);
CREATE INDEX fee_payment_allocations_payment_idx ON fee_payment_allocations (payment_id);
CREATE INDEX fee_payment_allocations_line_idx ON fee_payment_allocations (invoice_line_id);

-- The date the money was received (entered by the collector); paid_at stays the moment it was recorded.
-- collection_id groups the payments made together in one "Collect Fees" (one per bill touched).
ALTER TABLE fee_payments
    ADD COLUMN payment_date DATE,
    ADD COLUMN collection_id UUID;
UPDATE fee_payments SET payment_date = (paid_at AT TIME ZONE 'Asia/Kolkata')::date;
ALTER TABLE fee_payments ALTER COLUMN payment_date SET NOT NULL;
ALTER TABLE fee_payments ALTER COLUMN payment_date SET DEFAULT CURRENT_DATE;
CREATE INDEX fee_payments_collection_idx ON fee_payments (collection_id);

-- Smart School's payment modes.
ALTER TABLE fee_payments DROP CONSTRAINT fee_payments_method_check;
ALTER TABLE fee_payments ADD CONSTRAINT fee_payments_method_check
    CHECK (method IN ('CASH', 'CHEQUE', 'DD', 'BANK_TRANSFER', 'UPI', 'CARD', 'ONLINE', 'OTHER'));

-- --- Backfill lines for existing bills ------------------------------------------------------------------------
-- A bill whose amount still equals its structure's fee lines gets one line per fee line; any other bill
-- (adjusted amount, or a structure without lines) gets a single line for its whole amount.
INSERT INTO invoice_lines (invoice_id, label, fee_type_id, category, due_date, amount, sequence_order)
SELECT i.id, COALESCE(NULLIF(it.label, ''), ft.name, fs.name), it.fee_type_id, it.category,
       COALESCE(it.due_date, fs.due_date), it.amount, it.sequence_order
FROM invoices i
JOIN fee_structures fs ON fs.id = i.fee_structure_id
JOIN fee_structure_items it ON it.fee_structure_id = fs.id
LEFT JOIN fee_types ft ON ft.id = it.fee_type_id
WHERE i.amount = (SELECT sum(x.amount) FROM fee_structure_items x WHERE x.fee_structure_id = fs.id);

INSERT INTO invoice_lines (invoice_id, label, category, due_date, amount, sequence_order)
SELECT i.id, fs.name, 'OTHER', fs.due_date, i.amount, 0
FROM invoices i
JOIN fee_structures fs ON fs.id = i.fee_structure_id
WHERE NOT EXISTS (SELECT 1 FROM invoice_lines l WHERE l.invoice_id = i.id);

-- Spread each existing discount over its bill's lines in proportion; the last line absorbs the rounding.
WITH shares AS (
    SELECT l.id, l.invoice_id, i.discount_amount AS total_discount,
           CASE WHEN i.amount = 0 THEN 0 ELSE round(l.amount * i.discount_amount / i.amount, 2) END AS share,
           row_number() OVER (PARTITION BY l.invoice_id ORDER BY l.sequence_order DESC, l.id DESC) AS from_last
    FROM invoice_lines l JOIN invoices i ON i.id = l.invoice_id
    WHERE i.discount_amount > 0
),
sums AS (SELECT invoice_id, sum(share) AS shared FROM shares GROUP BY invoice_id)
UPDATE invoice_lines l
SET discount_amount = LEAST(l.amount, s.share + CASE WHEN s.from_last = 1 THEN s.total_discount - m.shared ELSE 0 END)
FROM shares s JOIN sums m ON m.invoice_id = s.invoice_id
WHERE l.id = s.id;
