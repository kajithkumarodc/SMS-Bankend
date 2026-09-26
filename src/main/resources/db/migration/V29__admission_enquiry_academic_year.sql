-- Links each admission enquiry to the academic session it was raised for, so the
-- Front Office dashboard can scope its counts to the current academic year the
-- same way admission cycles and student academic history already do (V24/V27).
-- Nullable: existing enquiries and any enquiry created while no academic year is
-- marked current stay unscoped rather than being backfilled with a guess.

ALTER TABLE admission_enquiries
    ADD COLUMN academic_year_id UUID REFERENCES academic_years(id) ON DELETE SET NULL;

CREATE INDEX admission_enquiries_academic_year_id_idx ON admission_enquiries(academic_year_id);
