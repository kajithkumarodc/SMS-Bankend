-- This deployment is permanently single-tenant (one school, one organization).
-- Removes the tenant_id/RLS layer added in V1-V2 and threaded through every
-- module since (V5-V17). Flyway migrations are one-way-door reviewed and never
-- edited after merge, so this is a new migration rather than a change to V1.
--
-- Order: drop policies -> disable RLS -> drop tenant-inclusive unique
-- constraints and add their plain replacements -> drop tenant_id columns
-- (this also drops each column's FK to tenants(id) and any index/check
-- constraint that referenced it) -> drop the tenants table itself.

-- --- 1. Drop RLS policies -----------------------------------------------

DROP POLICY schools_current_tenant_policy ON schools;
DROP POLICY users_current_tenant_policy ON users;
DROP POLICY roles_current_tenant_policy ON roles;
DROP POLICY permissions_current_tenant_policy ON permissions;
DROP POLICY user_roles_current_tenant_policy ON user_roles;
DROP POLICY students_current_tenant_policy ON students;
DROP POLICY attendance_records_current_tenant_policy ON attendance_records;
DROP POLICY classes_current_tenant_policy ON classes;
DROP POLICY sections_current_tenant_policy ON sections;
DROP POLICY audit_log_select_policy ON audit_log;
DROP POLICY audit_log_insert_policy ON audit_log;
DROP POLICY subjects_current_tenant_policy ON subjects;
DROP POLICY class_subjects_current_tenant_policy ON class_subjects;
DROP POLICY exams_current_tenant_policy ON exams;
DROP POLICY exam_marks_current_tenant_policy ON exam_marks;
DROP POLICY fee_structures_current_tenant_policy ON fee_structures;
DROP POLICY invoices_current_tenant_policy ON invoices;
DROP POLICY announcements_current_tenant_policy ON announcements;
DROP POLICY library_books_current_tenant_policy ON library_books;
DROP POLICY book_loans_current_tenant_policy ON book_loans;
DROP POLICY transport_routes_current_tenant_policy ON transport_routes;
DROP POLICY transport_vehicles_current_tenant_policy ON transport_vehicles;
DROP POLICY hostel_blocks_current_tenant_policy ON hostel_blocks;
DROP POLICY hostel_rooms_current_tenant_policy ON hostel_rooms;
DROP POLICY staff_profiles_current_tenant_policy ON staff_profiles;
DROP POLICY leave_requests_current_tenant_policy ON leave_requests;
DROP POLICY payroll_records_current_tenant_policy ON payroll_records;

-- --- 2. Disable RLS -------------------------------------------------------

ALTER TABLE schools NO FORCE ROW LEVEL SECURITY;
ALTER TABLE schools DISABLE ROW LEVEL SECURITY;
ALTER TABLE users NO FORCE ROW LEVEL SECURITY;
ALTER TABLE users DISABLE ROW LEVEL SECURITY;
ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE roles DISABLE ROW LEVEL SECURITY;
ALTER TABLE permissions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE permissions DISABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles DISABLE ROW LEVEL SECURITY;
ALTER TABLE students NO FORCE ROW LEVEL SECURITY;
ALTER TABLE students DISABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_records NO FORCE ROW LEVEL SECURITY;
ALTER TABLE attendance_records DISABLE ROW LEVEL SECURITY;
ALTER TABLE classes NO FORCE ROW LEVEL SECURITY;
ALTER TABLE classes DISABLE ROW LEVEL SECURITY;
ALTER TABLE sections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sections DISABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log NO FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_log DISABLE ROW LEVEL SECURITY;
ALTER TABLE subjects NO FORCE ROW LEVEL SECURITY;
ALTER TABLE subjects DISABLE ROW LEVEL SECURITY;
ALTER TABLE class_subjects NO FORCE ROW LEVEL SECURITY;
ALTER TABLE class_subjects DISABLE ROW LEVEL SECURITY;
ALTER TABLE exams NO FORCE ROW LEVEL SECURITY;
ALTER TABLE exams DISABLE ROW LEVEL SECURITY;
ALTER TABLE exam_marks NO FORCE ROW LEVEL SECURITY;
ALTER TABLE exam_marks DISABLE ROW LEVEL SECURITY;
ALTER TABLE fee_structures NO FORCE ROW LEVEL SECURITY;
ALTER TABLE fee_structures DISABLE ROW LEVEL SECURITY;
ALTER TABLE invoices NO FORCE ROW LEVEL SECURITY;
ALTER TABLE invoices DISABLE ROW LEVEL SECURITY;
ALTER TABLE announcements NO FORCE ROW LEVEL SECURITY;
ALTER TABLE announcements DISABLE ROW LEVEL SECURITY;
ALTER TABLE library_books NO FORCE ROW LEVEL SECURITY;
ALTER TABLE library_books DISABLE ROW LEVEL SECURITY;
ALTER TABLE book_loans NO FORCE ROW LEVEL SECURITY;
ALTER TABLE book_loans DISABLE ROW LEVEL SECURITY;
ALTER TABLE transport_routes NO FORCE ROW LEVEL SECURITY;
ALTER TABLE transport_routes DISABLE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE transport_vehicles DISABLE ROW LEVEL SECURITY;
ALTER TABLE hostel_blocks NO FORCE ROW LEVEL SECURITY;
ALTER TABLE hostel_blocks DISABLE ROW LEVEL SECURITY;
ALTER TABLE hostel_rooms NO FORCE ROW LEVEL SECURITY;
ALTER TABLE hostel_rooms DISABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profiles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE staff_profiles DISABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests NO FORCE ROW LEVEL SECURITY;
ALTER TABLE leave_requests DISABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_records NO FORCE ROW LEVEL SECURITY;
ALTER TABLE payroll_records DISABLE ROW LEVEL SECURITY;

-- --- 3. Replace tenant-inclusive unique constraints with plain equivalents -

ALTER TABLE users DROP CONSTRAINT users_tenant_email_key;
ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE roles DROP CONSTRAINT roles_tenant_name_key;
ALTER TABLE roles ADD CONSTRAINT roles_name_key UNIQUE (name);

ALTER TABLE permissions DROP CONSTRAINT permissions_tenant_name_key;
ALTER TABLE permissions ADD CONSTRAINT permissions_name_key UNIQUE (name);

ALTER TABLE students DROP CONSTRAINT students_tenant_admission_number_key;
ALTER TABLE students ADD CONSTRAINT students_admission_number_key UNIQUE (admission_number);

ALTER TABLE students DROP CONSTRAINT students_tenant_student_user_key;
ALTER TABLE students ADD CONSTRAINT students_student_user_id_key UNIQUE (student_user_id);

ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_tenant_student_date_key;
ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_student_id_date_key UNIQUE (student_id, date);

ALTER TABLE classes DROP CONSTRAINT classes_tenant_school_name_key;
ALTER TABLE classes ADD CONSTRAINT classes_school_id_name_key UNIQUE (school_id, name);

ALTER TABLE sections DROP CONSTRAINT sections_tenant_class_name_key;
ALTER TABLE sections ADD CONSTRAINT sections_class_id_name_key UNIQUE (class_id, name);

ALTER TABLE subjects DROP CONSTRAINT subjects_tenant_school_name_key;
ALTER TABLE subjects ADD CONSTRAINT subjects_school_id_name_key UNIQUE (school_id, name);

ALTER TABLE class_subjects DROP CONSTRAINT class_subjects_tenant_class_subject_key;
ALTER TABLE class_subjects ADD CONSTRAINT class_subjects_class_id_subject_id_key UNIQUE (class_id, subject_id);

ALTER TABLE exam_marks DROP CONSTRAINT exam_marks_tenant_exam_student_key;
ALTER TABLE exam_marks ADD CONSTRAINT exam_marks_exam_id_student_id_key UNIQUE (exam_id, student_id);

ALTER TABLE transport_vehicles DROP CONSTRAINT transport_vehicles_tenant_registration_key;
ALTER TABLE transport_vehicles ADD CONSTRAINT transport_vehicles_registration_number_key UNIQUE (registration_number);

ALTER TABLE hostel_rooms DROP CONSTRAINT hostel_rooms_tenant_block_number_key;
ALTER TABLE hostel_rooms ADD CONSTRAINT hostel_rooms_block_id_room_number_key UNIQUE (block_id, room_number);

ALTER TABLE staff_profiles DROP CONSTRAINT staff_profiles_tenant_user_key;
ALTER TABLE staff_profiles ADD CONSTRAINT staff_profiles_user_id_key UNIQUE (user_id);

ALTER TABLE staff_profiles DROP CONSTRAINT staff_profiles_tenant_employee_code_key;
ALTER TABLE staff_profiles ADD CONSTRAINT staff_profiles_employee_code_key UNIQUE (employee_code);

ALTER TABLE payroll_records DROP CONSTRAINT payroll_records_tenant_staff_month_year_key;
ALTER TABLE payroll_records ADD CONSTRAINT payroll_records_staff_user_id_month_year_key UNIQUE (staff_user_id, month, year);

-- --- 4. Drop tenant_id columns (cascades: FK to tenants(id), the
--        user_roles_tenant_consistency CHECK, and every tenant_id-leading
--        index on these columns) ----------------------------------------

ALTER TABLE schools DROP COLUMN tenant_id;
ALTER TABLE users DROP COLUMN tenant_id;
ALTER TABLE roles DROP COLUMN tenant_id;
ALTER TABLE permissions DROP COLUMN tenant_id;
ALTER TABLE user_roles DROP COLUMN tenant_id;
ALTER TABLE students DROP COLUMN tenant_id;
ALTER TABLE attendance_records DROP COLUMN tenant_id;
ALTER TABLE classes DROP COLUMN tenant_id;
ALTER TABLE sections DROP COLUMN tenant_id;
ALTER TABLE audit_log DROP COLUMN tenant_id;
ALTER TABLE subjects DROP COLUMN tenant_id;
ALTER TABLE class_subjects DROP COLUMN tenant_id;
ALTER TABLE exams DROP COLUMN tenant_id;
ALTER TABLE exam_marks DROP COLUMN tenant_id;
ALTER TABLE fee_structures DROP COLUMN tenant_id;
ALTER TABLE invoices DROP COLUMN tenant_id;
ALTER TABLE announcements DROP COLUMN tenant_id;
ALTER TABLE library_books DROP COLUMN tenant_id;
ALTER TABLE book_loans DROP COLUMN tenant_id;
ALTER TABLE transport_routes DROP COLUMN tenant_id;
ALTER TABLE transport_vehicles DROP COLUMN tenant_id;
ALTER TABLE hostel_blocks DROP COLUMN tenant_id;
ALTER TABLE hostel_rooms DROP COLUMN tenant_id;
ALTER TABLE staff_profiles DROP COLUMN tenant_id;
ALTER TABLE leave_requests DROP COLUMN tenant_id;
ALTER TABLE payroll_records DROP COLUMN tenant_id;

-- --- 5. Drop the control-plane table itself --------------------------------

DROP TABLE tenants;
