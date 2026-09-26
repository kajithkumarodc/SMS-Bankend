-- Admission Enquiry page redesign (Front Office V2): the enquiry form gains
-- Address, Description, Reference and Number Of Child, and the list shows a
-- "Last Follow Up Date" column next to the existing next-follow-up date.

-- --- 1. Configurable "Reference" lookup (who referred the enquirer) ----------
-- Same shape as enquiry_sources (V23): a lookup table, not a hardcoded enum.
-- Setup Front Office will manage these; a few defaults are seeded so the form's
-- Reference dropdown is usable from day one.

CREATE TABLE enquiry_references (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT enquiry_references_name_key UNIQUE (name)
);

INSERT INTO enquiry_references (id, name) VALUES
    (gen_random_uuid(), 'Staff'),
    (gen_random_uuid(), 'Parent'),
    (gen_random_uuid(), 'Student'),
    (gen_random_uuid(), 'Alumni'),
    (gen_random_uuid(), 'Friend / Relative'),
    (gen_random_uuid(), 'Other');

-- --- 2. New enquiry fields ------------------------------------------------------

ALTER TABLE admission_enquiries
    ADD COLUMN address TEXT,
    ADD COLUMN description TEXT,
    ADD COLUMN reference_id UUID REFERENCES enquiry_references(id) ON DELETE SET NULL,
    ADD COLUMN number_of_children SMALLINT,
    -- Denormalized date of the most recent follow-up actually made (the existing
    -- follow_up_date column is the *next* planned one). Kept in sync by
    -- EnquiryService#recordFollowUp, same as follow_up_date.
    ADD COLUMN last_follow_up_date DATE,
    ADD CONSTRAINT admission_enquiries_number_of_children_check
        CHECK (number_of_children IS NULL OR number_of_children BETWEEN 0 AND 99);

CREATE INDEX admission_enquiries_reference_id_idx ON admission_enquiries(reference_id);
-- The list's required From/To filter is on enquiry_date.
CREATE INDEX admission_enquiries_enquiry_date_idx ON admission_enquiries(enquiry_date);

UPDATE admission_enquiries e
SET last_follow_up_date = f.latest
FROM (SELECT enquiry_id, MAX(follow_up_date) AS latest FROM enquiry_follow_ups GROUP BY enquiry_id) f
WHERE f.enquiry_id = e.id;

-- --- 3. Retire the FOLLOW_UP status ---------------------------------------------
-- Recording a follow-up used to move an ACTIVE enquiry to FOLLOW_UP, which made it
-- drop out of the list's default "Active" filter the moment anyone called the
-- family. A follow-up is now just history (plus the last/next dates above); the
-- status stays whatever the operator set. Existing FOLLOW_UP rows were all
-- open leads, so they become ACTIVE.

UPDATE admission_enquiries SET status = 'ACTIVE' WHERE status = 'FOLLOW_UP';

ALTER TABLE admission_enquiries DROP CONSTRAINT admission_enquiries_status_check;
ALTER TABLE admission_enquiries ADD CONSTRAINT admission_enquiries_status_check
    CHECK (status IN ('ACTIVE', 'PASSIVE', 'WON', 'LOST', 'DEAD'));
