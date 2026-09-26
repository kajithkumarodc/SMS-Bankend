-- Fictional, repeatable demo seed for the migrated school-management schema (V1-V28).
-- Apply after Flyway migrations. Re-running does not duplicate the seeded records.
-- Does not create login accounts, sessions, audit history, or fake file metadata.
BEGIN;

DO $$
DECLARE
  v_school uuid;
  v_admin uuid;
  v_teacher uuid;
  v_parent uuid;
  v_accountant uuid;
  v_class uuid;
  v_section uuid;
  v_year uuid;
  v_cycle uuid;
  v_route uuid;
  v_block uuid;
  v_room uuid;
  v_structure uuid;
  v_discount uuid;
  v_student1 uuid;
  v_student2 uuid;
  v_student3 uuid;
  v_student4 uuid;
  v_subject1 uuid;
  v_subject2 uuid;
  v_book1 uuid;
  v_book2 uuid;
  v_invoice1 uuid;
  v_invoice2 uuid;
  v_enquiry uuid;
  v_application uuid;
  v_exam uuid;
BEGIN
  -- Keep role/permission catalogues complete; migrations provide permissions and mappings.
  INSERT INTO roles(name) VALUES ('SUPER_ADMIN'),('PRINCIPAL'),('SCHOOL_ADMIN'),('TEACHER'),('STUDENT'),('PARENT'),('ACCOUNTANT'),('LIBRARIAN'),('RECEPTIONIST') ON CONFLICT DO NOTHING;
  SELECT id INTO v_school FROM schools ORDER BY created_at NULLS FIRST, id LIMIT 1;
  IF v_school IS NULL THEN
    INSERT INTO schools(name,address) VALUES ('Maple Grove Public School','14 Lakeview Road, Mysuru, Karnataka 570001') RETURNING id INTO v_school;
  END IF;
  SELECT u.id INTO v_admin FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.name IN ('SCHOOL_ADMIN','PRINCIPAL','SUPER_ADMIN') ORDER BY CASE r.name WHEN 'SCHOOL_ADMIN' THEN 1 WHEN 'PRINCIPAL' THEN 2 ELSE 3 END LIMIT 1;
  SELECT u.id INTO v_teacher FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.name='TEACHER' ORDER BY u.id LIMIT 1;
  SELECT u.id INTO v_parent FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.name='PARENT' ORDER BY u.id LIMIT 1;
  SELECT u.id INTO v_accountant FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.name='ACCOUNTANT' ORDER BY u.id LIMIT 1;
  v_teacher := COALESCE(v_teacher,v_admin);
  v_accountant := COALESCE(v_accountant,v_admin);

  INSERT INTO academic_years(name,start_date,end_date,is_current) VALUES ('2026-2027','2026-04-01','2027-03-31',true) ON CONFLICT(name) DO NOTHING;
  SELECT id INTO v_year FROM academic_years WHERE name='2026-2027';
  INSERT INTO classes(id,school_id,name) VALUES
    ('a1000000-0000-4000-8000-000000000001',v_school,'Grade 5'),
    ('a1000000-0000-4000-8000-000000000002',v_school,'Grade 6'),
    ('a1000000-0000-4000-8000-000000000003',v_school,'Grade 7') ON CONFLICT DO NOTHING;
  SELECT id INTO v_class FROM classes WHERE school_id=v_school AND name='Grade 5' ORDER BY id LIMIT 1;
  INSERT INTO sections(id,class_id,name) VALUES
    ('a2000000-0000-4000-8000-000000000001',v_class,'A'),('a2000000-0000-4000-8000-000000000002',v_class,'B') ON CONFLICT DO NOTHING;
  SELECT id INTO v_section FROM sections WHERE class_id=v_class AND name='A' ORDER BY id LIMIT 1;

  INSERT INTO subjects(id,school_id,name) VALUES
    ('a3000000-0000-4000-8000-000000000001',v_school,'Mathematics'),('a3000000-0000-4000-8000-000000000002',v_school,'English'),('a3000000-0000-4000-8000-000000000003',v_school,'Science'),('a3000000-0000-4000-8000-000000000004',v_school,'Environmental Studies') ON CONFLICT DO NOTHING;
  SELECT id INTO v_subject1 FROM subjects WHERE school_id=v_school AND name='Mathematics' ORDER BY id LIMIT 1;
  SELECT id INTO v_subject2 FROM subjects WHERE school_id=v_school AND name='English' ORDER BY id LIMIT 1;
  INSERT INTO class_subjects(class_id,subject_id,teacher_id) VALUES (v_class,v_subject1,v_teacher),(v_class,v_subject2,v_teacher) ON CONFLICT DO NOTHING;

  INSERT INTO transport_routes(id,name) VALUES ('a4000000-0000-4000-8000-000000000001','Lakeview Morning Route') ON CONFLICT DO NOTHING;
  SELECT id INTO v_route FROM transport_routes WHERE name='Lakeview Morning Route' ORDER BY id LIMIT 1;
  INSERT INTO transport_vehicles(route_id,registration_number,driver_name,driver_contact,capacity) VALUES (v_route,'KA09DE4821','Ravi Narayan','+91-90000-10001',32) ON CONFLICT DO NOTHING;
  INSERT INTO hostel_blocks(id,name) VALUES ('a5000000-0000-4000-8000-000000000001','Cedar House') ON CONFLICT DO NOTHING;
  SELECT id INTO v_block FROM hostel_blocks WHERE name='Cedar House' ORDER BY id LIMIT 1;
  INSERT INTO hostel_rooms(block_id,room_number,capacity) VALUES (v_block,'A-101',4),(v_block,'A-102',4) ON CONFLICT DO NOTHING;
  SELECT id INTO v_room FROM hostel_rooms WHERE block_id=v_block AND room_number='A-101' ORDER BY id LIMIT 1;

  INSERT INTO students(id,school_id,full_name,first_name,last_name,date_of_birth,admission_number,guardian_name,guardian_contact,status,section_id,guardian_user_id,transport_route_id,hostel_room_id,blood_group,guardian_relationship,guardian_phone,guardian_email,gender,admission_date,roll_number,nationality,mother_tongue,guardian_occupation,father_name,father_mobile,father_email,father_occupation,mother_name,mother_mobile,mother_email,mother_occupation,emergency_contact_name,emergency_contact_relationship,emergency_contact_mobile,address_line1,city,state,pincode,preferred_language)
  VALUES
   ('a6000000-0000-4000-8000-000000000001',v_school,'Aarav Sharma','Aarav','Sharma','2015-02-14','DEMO-2627-001','Meera Sharma','+91-90000-11001','ACTIVE',v_section,v_parent,v_route,NULL,'O+','MOTHER','+91-90000-11001','meera.sharma@example.test','MALE','2026-04-06','05A01','Indian','Kannada','Architect','Rohan Sharma','+91-90000-11002','rohan.sharma@example.test','Architect','Meera Sharma','+91-90000-11001','meera.sharma@example.test','Teacher','Nisha Rao','Aunt','+91-90000-11003','12 Jasmine Enclave','Mysuru','Karnataka','570001','ENGLISH'),
   ('a6000000-0000-4000-8000-000000000002',v_school,'Ananya Iyer','Ananya','Iyer','2015-07-22','DEMO-2627-002','Kavita Iyer','+91-90000-12001','ACTIVE',v_section,v_parent,v_route,NULL,'A+','MOTHER','+91-90000-12001','kavita.iyer@example.test','FEMALE','2026-04-06','05A02','Indian','Tamil','Doctor','Arun Iyer','+91-90000-12002','arun.iyer@example.test','Engineer','Kavita Iyer','+91-90000-12001','kavita.iyer@example.test','Doctor','Suresh Iyer','Grandfather','+91-90000-12003','8 Banyan Park','Mysuru','Karnataka','570002','ENGLISH'),
   ('a6000000-0000-4000-8000-000000000003',v_school,'Kabir Khan','Kabir','Khan','2015-11-03','DEMO-2627-003','Farah Khan','+91-90000-13001','ACTIVE',v_section,v_parent,NULL,v_room,'B+','MOTHER','+91-90000-13001','farah.khan@example.test','MALE','2026-04-06','05A03','Indian','Urdu','Small business owner','Imran Khan','+91-90000-13002','imran.khan@example.test','Shop owner','Farah Khan','+91-90000-13001','farah.khan@example.test','Designer','Amina Begum','Grandmother','+91-90000-13003','41 Greenfield Layout','Mysuru','Karnataka','570003','ENGLISH'),
   ('a6000000-0000-4000-8000-000000000004',v_school,'Diya Nair','Diya','Nair','2015-04-19','DEMO-2627-004','Lakshmi Nair','+91-90000-14001','ACTIVE',v_section,v_parent,NULL,NULL,'AB+','MOTHER','+91-90000-14001','lakshmi.nair@example.test','FEMALE','2026-04-06','05A04','Indian','Malayalam','Accountant','Vivek Nair','+91-90000-14002','vivek.nair@example.test','Accountant','Lakshmi Nair','+91-90000-14001','lakshmi.nair@example.test','Accountant','Maya Nair','Aunt','+91-90000-14003','27 Hillcrest Avenue','Mysuru','Karnataka','570004','ENGLISH') ON CONFLICT DO NOTHING;
  UPDATE students SET guardian_user_id=v_parent WHERE admission_number LIKE 'DEMO-2627-%' AND guardian_user_id IS NULL AND v_parent IS NOT NULL;
  SELECT id INTO v_student1 FROM students WHERE admission_number='DEMO-2627-001';
  SELECT id INTO v_student2 FROM students WHERE admission_number='DEMO-2627-002';
  SELECT id INTO v_student3 FROM students WHERE admission_number='DEMO-2627-003';
  SELECT id INTO v_student4 FROM students WHERE admission_number='DEMO-2627-004';
  INSERT INTO student_academic_history(id,student_id,academic_year_id,class_id,section_id,recorded_by_user_id,change_reason) VALUES
   ('a6100000-0000-4000-8000-000000000001',v_student1,v_year,v_class,v_section,v_admin,'ADMISSION'),('a6100000-0000-4000-8000-000000000002',v_student2,v_year,v_class,v_section,v_admin,'ADMISSION'),('a6100000-0000-4000-8000-000000000003',v_student3,v_year,v_class,v_section,v_admin,'ADMISSION'),('a6100000-0000-4000-8000-000000000004',v_student4,v_year,v_class,v_section,v_admin,'ADMISSION') ON CONFLICT DO NOTHING;
  INSERT INTO student_identifications(student_id,id_type,id_value,notes) VALUES (v_student1,'SCHOOL_ID','DEMO-ID-2627-001','Fictional demo identifier'),(v_student2,'SCHOOL_ID','DEMO-ID-2627-002','Fictional demo identifier'),(v_student3,'SCHOOL_ID','DEMO-ID-2627-003','Fictional demo identifier'),(v_student4,'SCHOOL_ID','DEMO-ID-2627-004','Fictional demo identifier') ON CONFLICT DO NOTHING;

  INSERT INTO attendance_records(student_id,date,status,marked_by) VALUES
   (v_student1,'2026-09-21','PRESENT',v_teacher),(v_student2,'2026-09-21','PRESENT',v_teacher),(v_student3,'2026-09-21','LATE',v_teacher),(v_student4,'2026-09-21','ABSENT',v_teacher),
   (v_student1,'2026-09-22','PRESENT',v_teacher),(v_student2,'2026-09-22','LATE',v_teacher),(v_student3,'2026-09-22','PRESENT',v_teacher),(v_student4,'2026-09-22','PRESENT',v_teacher) ON CONFLICT DO NOTHING;
  INSERT INTO exams(id,class_id,subject_id,name,exam_date,max_marks) VALUES ('a6200000-0000-4000-8000-000000000001',v_class,v_subject1,'Term 1 Mathematics','2026-09-18',100),('a6200000-0000-4000-8000-000000000002',v_class,v_subject2,'Term 1 English','2026-09-19',100) ON CONFLICT DO NOTHING;
  SELECT id INTO v_exam FROM exams WHERE class_id=v_class AND name='Term 1 Mathematics' ORDER BY id LIMIT 1;
  INSERT INTO exam_marks(exam_id,student_id,marks_obtained) VALUES (v_exam,v_student1,92),(v_exam,v_student2,88),(v_exam,v_student3,81),(v_exam,v_student4,95) ON CONFLICT DO NOTHING;

  INSERT INTO fee_types(name) VALUES ('Tuition Fee'),('Admission Fee'),('Examination Fee'),('Transport Fee'),('Library Fee'),('Hostel Fee'),('Activity Fee'),('Other') ON CONFLICT DO NOTHING;
  INSERT INTO fee_structures(id,school_id,name,amount,due_date,class_id,academic_year,frequency,late_fee_amount,status) VALUES ('a7000000-0000-4000-8000-000000000001',v_school,'Grade 5 Term 1 Fees',18000,'2026-10-15',v_class,'2026-2027','QUARTERLY',250,'ACTIVE') ON CONFLICT DO NOTHING;
  SELECT id INTO v_structure FROM fee_structures WHERE name='Grade 5 Term 1 Fees' AND school_id=v_school ORDER BY id LIMIT 1;
  INSERT INTO fee_structure_items(id,fee_structure_id,category,label,amount,sequence_order,fee_type_id) SELECT x.id,v_structure,'TERM_1',x.label,x.amount,x.seq,ft.id FROM (VALUES ('a7100000-0000-4000-8000-000000000001'::uuid,'Tuition Fee',12000,1),('a7100000-0000-4000-8000-000000000002'::uuid,'Transport Fee',3000,2),('a7100000-0000-4000-8000-000000000003'::uuid,'Examination Fee',1500,3),('a7100000-0000-4000-8000-000000000004'::uuid,'Activity Fee',1500,4)) x(id,label,amount,seq) JOIN fee_types ft ON ft.name=x.label ON CONFLICT DO NOTHING;
  INSERT INTO fee_discounts(id,name,discount_type,value,fee_structure_id,valid_from,valid_to,status) VALUES ('a8000000-0000-4000-8000-000000000001','Sibling Support','PERCENTAGE',10,v_structure,'2026-04-01','2027-03-31','ACTIVE') ON CONFLICT DO NOTHING;
  SELECT id INTO v_discount FROM fee_discounts WHERE name='Sibling Support' AND fee_structure_id=v_structure ORDER BY id LIMIT 1;
  INSERT INTO invoices(id,student_id,fee_structure_id,amount,status,discount_id,discount_amount,net_amount,late_fee_amount,paid_amount,assigned_by_user_id) VALUES
   ('a9000000-0000-4000-8000-000000000001',v_student1,v_structure,18000,'PARTIALLY_PAID',v_discount,1800,16200,0,8000,v_accountant),
   ('a9000000-0000-4000-8000-000000000002',v_student2,v_structure,18000,'PENDING',NULL,0,18000,0,0,v_accountant),
   ('a9000000-0000-4000-8000-000000000003',v_student3,v_structure,18000,'PAID',NULL,0,18000,0,18000,v_accountant),
   ('a9000000-0000-4000-8000-000000000004',v_student4,v_structure,18000,'PENDING',NULL,0,18000,0,0,v_accountant) ON CONFLICT DO NOTHING;
  v_invoice1 := 'a9000000-0000-4000-8000-000000000001'; v_invoice2 := 'a9000000-0000-4000-8000-000000000003';
  INSERT INTO fee_payments(invoice_id,type,amount,method,reference_number,receipt_number,collected_by_user_id,notes) VALUES
    (v_invoice1,'PAYMENT',8000,'ONLINE','DEMO-UPI-2627-001','DEMO-RCPT-2627-001',v_accountant,'Fictional partial payment'),
    (v_invoice2,'PAYMENT',18000,'CASH','DEMO-CASH-2627-003','DEMO-RCPT-2627-003',v_accountant,'Fictional term fee payment') ON CONFLICT DO NOTHING;

  INSERT INTO announcements(id,title,body,created_by) VALUES
    ('aa100000-0000-4000-8000-000000000001','Welcome to Term 1','Welcome back, students and families. Classes begin at 8:30 AM. Please check the academic calendar for upcoming events.',COALESCE(v_admin,v_teacher)),
    ('aa100000-0000-4000-8000-000000000002','Science Discovery Day','Grade 5 will present hands-on science projects in the library hall on 16 October. Families are welcome.',COALESCE(v_admin,v_teacher)) ON CONFLICT DO NOTHING;
  INSERT INTO library_books(id,title,author,isbn,total_copies,available_copies) VALUES
    ('aa000000-0000-4000-8000-000000000001','The Blue Umbrella','Ruskin Bond','9788186939560',8,7),
    ('aa000000-0000-4000-8000-000000000002','Wings of Fire: An Autobiography','A. P. J. Abdul Kalam','9788173711466',6,5) ON CONFLICT DO NOTHING;
  SELECT id INTO v_book1 FROM library_books WHERE isbn='9788186939560'; SELECT id INTO v_book2 FROM library_books WHERE isbn='9788173711466';
  INSERT INTO book_loans(id,book_id,student_id,issued_date,due_date,returned_date) VALUES ('aa200000-0000-4000-8000-000000000001',v_book1,v_student1,'2026-09-15','2026-09-29',NULL),('aa200000-0000-4000-8000-000000000002',v_book2,v_student2,'2026-09-10','2026-09-24','2026-09-22') ON CONFLICT DO NOTHING;

  INSERT INTO staff_profiles(user_id,employee_code,department,designation,date_of_joining,salary_amount,status) SELECT v_teacher,'DEMO-EMP-001','Academics','Class Teacher','2022-06-01',42000,'ACTIVE' WHERE v_teacher IS NOT NULL ON CONFLICT DO NOTHING;
  INSERT INTO leave_requests(id,staff_user_id,leave_type,start_date,end_date,status,reason) SELECT 'aa300000-0000-4000-8000-000000000001',v_teacher,'CASUAL','2026-10-05','2026-10-06','APPROVED','Fictional family commitment' WHERE v_teacher IS NOT NULL ON CONFLICT DO NOTHING;
  INSERT INTO payroll_records(staff_user_id,month,year,base_salary,deductions,net_pay,status,paid_at) SELECT v_teacher,8,2026,42000,2000,40000,'PAID','2026-09-01' WHERE v_teacher IS NOT NULL ON CONFLICT DO NOTHING;

  INSERT INTO admission_cycles(id,school_id,academic_year_id,name,description,open_date,close_date,status,created_by_user_id) VALUES ('ab000000-0000-4000-8000-000000000001',v_school,v_year,'2027-2028 Admissions','Fictional demo intake for the next academic year.','2026-09-01','2027-02-28','OPEN',v_admin) ON CONFLICT DO NOTHING;
  SELECT id INTO v_cycle FROM admission_cycles WHERE school_id=v_school AND name='2027-2028 Admissions' ORDER BY id LIMIT 1;
  INSERT INTO admission_applications(id,application_number,admission_cycle_id,school_id,status,first_name,middle_name,last_name,date_of_birth,gender,blood_group,nationality,religion,mother_tongue,category,applying_class_id,previous_school_name,previous_school_class,admission_source,guardian_name,guardian_relationship,guardian_phone,guardian_email,father_name,father_mobile,mother_name,mother_mobile,address_line1,city,state,country,pincode,submitted_at)
  VALUES ('ac000000-0000-4000-8000-000000000001','APP-DEMO-2728-001',v_cycle,v_school,'SUBMITTED','Ishaan','Dev','Reddy','2016-08-12','MALE','O+','Indian','Hindu','Telugu','GENERAL',(SELECT id FROM classes WHERE school_id=v_school AND name='Grade 6' ORDER BY id LIMIT 1),'Sunrise Primary School','Grade 4','REFERRAL','Sanjana Reddy','Mother','+91-90000-15001','sanjana.reddy@example.test','Kiran Reddy','+91-90000-15002','Sanjana Reddy','+91-90000-15001','5 Mango Street','Mysuru','Karnataka','India','570005','2026-09-12'),
   ('ac000000-0000-4000-8000-000000000002','APP-DEMO-2728-002',v_cycle,v_school,'UNDER_REVIEW','Tara','Maya','Desai','2016-03-28','FEMALE','A-','Indian','Jain','Gujarati','GENERAL',(SELECT id FROM classes WHERE school_id=v_school AND name='Grade 6' ORDER BY id LIMIT 1),'Little Oaks Academy','Grade 4','WEBSITE','Neelam Desai','Mother','+91-90000-16001','neelam.desai@example.test','Amit Desai','+91-90000-16002','Neelam Desai','+91-90000-16001','19 Riverbend Lane','Mysuru','Karnataka','India','570006','2026-09-14') ON CONFLICT DO NOTHING;
  SELECT id INTO v_application FROM admission_applications WHERE application_number='APP-DEMO-2728-001';
  INSERT INTO admission_enquiries(enquiry_number,applicant_name,guardian_name,phone,email,class_id,enquiry_date,source_id,assigned_staff_user_id,follow_up_date,follow_up_notes,status,remarks,admission_application_id)
  SELECT 'ENQ-DEMO-2627-001','Mira Kulkarni','Pooja Kulkarni','+91-90000-17001','pooja.kulkarni@example.test',v_class,'2026-09-20',es.id,v_admin,'2026-09-28','Share Grade 5 curriculum and school tour timings','ACTIVE','Fictional enquiry created for demo.',NULL FROM enquiry_sources es WHERE es.name='Website' ORDER BY es.id LIMIT 1 ON CONFLICT DO NOTHING;
  SELECT id INTO v_enquiry FROM admission_enquiries WHERE enquiry_number='ENQ-DEMO-2627-001';
  INSERT INTO enquiry_follow_ups(id,enquiry_id,follow_up_date,follow_up_type,notes,staff_user_id,next_follow_up_date) SELECT 'ac100000-0000-4000-8000-000000000001',v_enquiry,'2026-09-21','CALL','Answered initial questions; family plans to visit campus.',v_admin,'2026-09-28' WHERE v_enquiry IS NOT NULL ON CONFLICT DO NOTHING;

  -- Keep role definitions available and give the existing staff demo account its catalogued role.
  IF v_teacher IS NOT NULL THEN INSERT INTO user_roles(user_id,role_id) SELECT v_teacher,id FROM roles WHERE name='TEACHER' ON CONFLICT DO NOTHING; END IF;
  IF v_admin IS NOT NULL THEN INSERT INTO user_roles(user_id,role_id) SELECT v_admin,id FROM roles WHERE name='SCHOOL_ADMIN' ON CONFLICT DO NOTHING; END IF;
END $$;
COMMIT;
