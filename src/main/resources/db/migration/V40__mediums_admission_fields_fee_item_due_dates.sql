-- Student Admission (Smart School layout) + fees that vary by class AND medium of instruction.

-- --- Mediums of instruction (English Medium, Tamil Medium, ...) -----------------------------
-- Editable list. A student studies in one medium; a fee structure can be for one medium only
-- (fee_structures.medium_id) or for every medium (NULL).
CREATE TABLE mediums (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX mediums_name_idx ON mediums (lower(name));

INSERT INTO mediums (name) VALUES ('English Medium'), ('Tamil Medium');

-- --- Student fields from the admission form that had no column yet -----------------------------
ALTER TABLE students
    ADD COLUMN medium_id UUID REFERENCES mediums (id),
    ADD COLUMN caste VARCHAR(100),
    ADD COLUMN mobile_number VARCHAR(20),
    ADD COLUMN email VARCHAR(200),
    ADD COLUMN height VARCHAR(20),
    ADD COLUMN weight VARCHAR(20),
    ADD COLUMN measurement_date DATE,
    ADD COLUMN medical_history TEXT,
    ADD COLUMN guardian_address TEXT,
    ADD COLUMN bank_account_number VARCHAR(40),
    ADD COLUMN bank_name VARCHAR(150),
    ADD COLUMN ifsc_code VARCHAR(20),
    ADD COLUMN note TEXT;

-- --- Fees ---------------------------------------------------------------------------------
ALTER TABLE fee_structures ADD COLUMN medium_id UUID REFERENCES mediums (id);

-- Each fee line (e.g. "June Month Fees") can have its own due date; NULL = the structure's due date.
ALTER TABLE fee_structure_items ADD COLUMN due_date DATE;
