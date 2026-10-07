-- Human Resource -> Staff Directory: the full staff record (personal details, payroll, leave
-- entitlements, bank account, social links, photo and documents) on top of V17's staff_profiles,
-- plus the Department and Designation lookup lists the Add Staff form picks from.

-- --- 1. Departments and designations ----------------------------------------------------
-- Same lookup-table shape as expense_heads (V43).

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT departments_name_key UNIQUE (name)
);

CREATE TABLE designations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT designations_name_key UNIQUE (name)
);

INSERT INTO departments (id, name) VALUES
    (gen_random_uuid(), 'Admin'),
    (gen_random_uuid(), 'Academic'),
    (gen_random_uuid(), 'Finance'),
    (gen_random_uuid(), 'Library'),
    (gen_random_uuid(), 'Transport'),
    (gen_random_uuid(), 'Hostel');

INSERT INTO designations (id, name) VALUES
    (gen_random_uuid(), 'Principal'),
    (gen_random_uuid(), 'Technical Head'),
    (gen_random_uuid(), 'Faculty'),
    (gen_random_uuid(), 'Accountant'),
    (gen_random_uuid(), 'Librarian'),
    (gen_random_uuid(), 'Receptionist');

-- Keep any free-text department/designation already typed on a staff profile (V17) as a list entry.
INSERT INTO departments (id, name)
SELECT gen_random_uuid(), d.name FROM (
    SELECT DISTINCT btrim(department) AS name FROM staff_profiles WHERE btrim(coalesce(department, '')) <> ''
) d
WHERE NOT EXISTS (SELECT 1 FROM departments x WHERE x.name = d.name);

INSERT INTO designations (id, name)
SELECT gen_random_uuid(), d.name FROM (
    SELECT DISTINCT btrim(designation) AS name FROM staff_profiles WHERE btrim(coalesce(designation, '')) <> ''
) d
WHERE NOT EXISTS (SELECT 1 FROM designations x WHERE x.name = d.name);

-- --- 2. Staff directory columns -----------------------------------------------------------
-- The date of joining and salary are optional on the Add Staff form (V17 required both).
-- `department` / `designation` (text) stay as the display names the older Staff page reads;
-- the directory sets them together with the new ids.

ALTER TABLE staff_profiles
    ALTER COLUMN date_of_joining DROP NOT NULL,
    ALTER COLUMN salary_amount SET DEFAULT 0,
    ADD COLUMN department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    ADD COLUMN designation_id UUID REFERENCES designations(id) ON DELETE SET NULL,
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100),
    ADD COLUMN father_name VARCHAR(200),
    ADD COLUMN mother_name VARCHAR(200),
    ADD COLUMN gender VARCHAR(10),
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN date_of_leaving DATE,
    ADD COLUMN phone VARCHAR(30),
    ADD COLUMN emergency_contact_number VARCHAR(30),
    ADD COLUMN marital_status VARCHAR(20),
    ADD COLUMN current_address VARCHAR(500),
    ADD COLUMN permanent_address VARCHAR(500),
    ADD COLUMN qualification VARCHAR(500),
    ADD COLUMN work_experience VARCHAR(500),
    ADD COLUMN note TEXT,
    ADD COLUMN pan_number VARCHAR(20),
    -- Payroll
    ADD COLUMN epf_no VARCHAR(50),
    ADD COLUMN contract_type VARCHAR(20),
    ADD COLUMN work_shift VARCHAR(100),
    ADD COLUMN work_location VARCHAR(100),
    -- Leave entitlements (days per year)
    ADD COLUMN medical_leave INTEGER,
    ADD COLUMN casual_leave INTEGER,
    ADD COLUMN maternity_leave INTEGER,
    ADD COLUMN sick_leave INTEGER,
    ADD COLUMN mandatory_leave INTEGER,
    -- Bank account
    ADD COLUMN account_title VARCHAR(200),
    ADD COLUMN bank_account_number VARCHAR(40),
    ADD COLUMN bank_name VARCHAR(150),
    ADD COLUMN ifsc_code VARCHAR(20),
    ADD COLUMN bank_branch_name VARCHAR(150),
    -- Social links
    ADD COLUMN facebook_url VARCHAR(300),
    ADD COLUMN twitter_url VARCHAR(300),
    ADD COLUMN linkedin_url VARCHAR(300),
    ADD COLUMN instagram_url VARCHAR(300),
    -- Photo, stored under app.storage.base-dir/staff/<id>/
    ADD COLUMN photo_original_filename VARCHAR(255),
    ADD COLUMN photo_stored_filename VARCHAR(100),
    ADD COLUMN photo_content_type VARCHAR(150),
    ADD CONSTRAINT staff_profiles_gender_check CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    ADD CONSTRAINT staff_profiles_marital_status_check CHECK (
        marital_status IS NULL OR marital_status IN ('SINGLE', 'MARRIED', 'WIDOWED', 'SEPARATED', 'NOT_SPECIFIED')),
    ADD CONSTRAINT staff_profiles_contract_type_check CHECK (
        contract_type IS NULL OR contract_type IN ('PERMANENT', 'PROBATION')),
    ADD CONSTRAINT staff_profiles_leaves_check CHECK (
        coalesce(medical_leave, 0) >= 0 AND coalesce(casual_leave, 0) >= 0 AND coalesce(maternity_leave, 0) >= 0
        AND coalesce(sick_leave, 0) >= 0 AND coalesce(mandatory_leave, 0) >= 0),
    ADD CONSTRAINT staff_profiles_photo_check CHECK ((photo_stored_filename IS NULL) = (photo_original_filename IS NULL));

-- Backfill the new ids for profiles that already had free-text values.
UPDATE staff_profiles p SET department_id = d.id FROM departments d WHERE d.name = btrim(p.department);
UPDATE staff_profiles p SET designation_id = d.id FROM designations d WHERE d.name = btrim(p.designation);

CREATE INDEX staff_profiles_department_id_idx ON staff_profiles(department_id);
CREATE INDEX staff_profiles_designation_id_idx ON staff_profiles(designation_id);

-- --- 3. Staff documents ----------------------------------------------------------------------
-- One file per kind per staff member (Resume, Joining Letter, Resignation Letter, Other Documents),
-- stored under app.storage.base-dir/staff/<staff id>/.

CREATE TABLE staff_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_profile_id UUID NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(100) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT staff_documents_kind_check CHECK (kind IN ('RESUME', 'JOINING_LETTER', 'RESIGNATION_LETTER', 'OTHER')),
    CONSTRAINT staff_documents_staff_kind_key UNIQUE (staff_profile_id, kind)
);

-- STAFF_VIEW / STAFF_CREATE / STAFF_EDIT / STAFF_DELETE / STAFF_EXPORT already exist (V22) and
-- are granted to the admin roles there; the directory reuses them.
