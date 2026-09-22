-- Extra admission-form fields for the "Add student" wizard (SIS UX pass):
-- blood group, address, and a proper guardian relationship + split
-- phone/email in place of the single free-text guardian_contact. All
-- nullable -- existing rows and the existing guardian_contact column and
-- its readers/writers (e.g. EditStudentModal) are unaffected.

ALTER TABLE students
    ADD COLUMN blood_group VARCHAR(5),
    ADD COLUMN address TEXT,
    ADD COLUMN guardian_relationship VARCHAR(20),
    ADD COLUMN guardian_phone VARCHAR(30),
    ADD COLUMN guardian_email VARCHAR(200);

ALTER TABLE students ADD CONSTRAINT students_guardian_relationship_check
    CHECK (guardian_relationship IN ('FATHER', 'MOTHER', 'GUARDIAN', 'OTHER'));

ALTER TABLE students ADD CONSTRAINT students_blood_group_check
    CHECK (blood_group IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'));
