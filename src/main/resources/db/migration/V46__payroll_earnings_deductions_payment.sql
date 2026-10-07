-- Human Resource -> Payroll: a payroll record can carry named earnings and deductions plus tax, and is paid
-- with a payment mode, date and note. Builds on V17's payroll_records (status PENDING = "Generated", PAID).
--   net_pay = base_salary + earnings - deductions - tax
-- `earnings` and `deductions` hold the totals of the lines in payroll_items; records created before this
-- migration have no lines and keep their `deductions` total.

ALTER TABLE payroll_records DROP CONSTRAINT payroll_records_net_pay_check;

ALTER TABLE payroll_records
    ADD COLUMN earnings NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN tax NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN payment_mode VARCHAR(30),
    ADD COLUMN payment_date DATE,
    ADD COLUMN payment_note VARCHAR(500),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT payroll_records_earnings_check CHECK (earnings >= 0),
    ADD CONSTRAINT payroll_records_tax_check CHECK (tax >= 0),
    ADD CONSTRAINT payroll_records_net_pay_check CHECK (net_pay = base_salary + earnings - deductions - tax),
    ADD CONSTRAINT payroll_records_payment_mode_check CHECK (
        payment_mode IS NULL OR payment_mode IN ('CASH', 'CHEQUE', 'BANK_TRANSFER'));

CREATE TABLE payroll_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_record_id UUID NOT NULL REFERENCES payroll_records(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    type VARCHAR(100) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT payroll_items_kind_check CHECK (kind IN ('EARNING', 'DEDUCTION')),
    CONSTRAINT payroll_items_amount_check CHECK (amount >= 0)
);

CREATE INDEX payroll_items_record_idx ON payroll_items(payroll_record_id);

-- PAYROLL_VIEW / PAYROLL_CREATE / PAYROLL_APPROVE / PAYROLL_EXPORT / PAYROLL_PRINT already exist (V22) and are
-- granted to the admin roles there (the principal has view and approve); the payroll pages reuse them.
