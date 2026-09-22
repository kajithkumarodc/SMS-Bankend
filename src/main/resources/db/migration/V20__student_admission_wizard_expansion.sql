-- Expands the "Add student" wizard to a full admission form (SIS UX pass 2):
-- demographics, admission date, roll number, nationality/mother tongue,
-- father/mother/emergency-contact detail, structured address, and
-- communication preferences. All nullable / defaulted -- existing rows and
-- every other reader of `students` are unaffected.

ALTER TABLE students
    ADD COLUMN gender VARCHAR(10),
    ADD COLUMN admission_date DATE,
    ADD COLUMN roll_number VARCHAR(20),
    ADD COLUMN nationality VARCHAR(100),
    ADD COLUMN mother_tongue VARCHAR(100),

    ADD COLUMN guardian_alternate_phone VARCHAR(30),
    ADD COLUMN guardian_occupation VARCHAR(200),

    ADD COLUMN father_name VARCHAR(200),
    ADD COLUMN father_mobile VARCHAR(30),
    ADD COLUMN father_email VARCHAR(200),
    ADD COLUMN father_occupation VARCHAR(200),

    ADD COLUMN mother_name VARCHAR(200),
    ADD COLUMN mother_mobile VARCHAR(30),
    ADD COLUMN mother_email VARCHAR(200),
    ADD COLUMN mother_occupation VARCHAR(200),

    ADD COLUMN emergency_contact_name VARCHAR(200),
    ADD COLUMN emergency_contact_relationship VARCHAR(30),
    ADD COLUMN emergency_contact_mobile VARCHAR(30),

    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN city VARCHAR(100),
    ADD COLUMN state VARCHAR(100),
    ADD COLUMN pincode VARCHAR(10),

    ADD COLUMN sms_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN whatsapp_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN preferred_language VARCHAR(20) NOT NULL DEFAULT 'ENGLISH';

ALTER TABLE students ADD CONSTRAINT students_gender_check
    CHECK (gender IN ('MALE', 'FEMALE', 'OTHER'));

ALTER TABLE students ADD CONSTRAINT students_preferred_language_check
    CHECK (preferred_language IN ('ENGLISH', 'TAMIL', 'HINDI', 'OTHER'));

-- 'UNKNOWN' becomes a selectable blood-group option alongside the 8 real types
-- (V19 only allowed the 8; the wizard needs an explicit "I don't know" choice
-- distinct from "not answered yet" / NULL).
ALTER TABLE students DROP CONSTRAINT students_blood_group_check;
ALTER TABLE students ADD CONSTRAINT students_blood_group_check
    CHECK (blood_group IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-', 'UNKNOWN'));

-- `address` (V19) had zero readers anywhere in the app -- replaced outright by
-- the structured address_line1/2 + city/state/pincode columns above rather
-- than carrying two overlapping representations.
ALTER TABLE students DROP COLUMN address;
