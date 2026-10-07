-- V29 added academic_year_id but left every existing enquiry NULL. Now that the
-- Front Office dashboard (EnquiryService#summary) scopes its counts to whichever
-- academic year is current, those pre-existing enquiries would silently vanish
-- from the dashboard the moment a current year is set -- they predate the concept
-- of an enquiry academic year, so the only sensible one-time assignment is the
-- year that's current right now. New migration rather than editing V29, same
-- one-way-door policy V18 documents.

UPDATE admission_enquiries
SET academic_year_id = (SELECT id FROM academic_years WHERE is_current = true LIMIT 1)
WHERE academic_year_id IS NULL
  AND EXISTS (SELECT 1 FROM academic_years WHERE is_current = true);
