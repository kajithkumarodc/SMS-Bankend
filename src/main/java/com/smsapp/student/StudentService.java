package com.smsapp.student;

import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final SectionRepository sectionRepository;
    private final AcademicHistoryService academicHistoryService;
    private final AuditService auditService;

    public StudentService(StudentRepository studentRepository, SchoolRepository schoolRepository,
                          SectionRepository sectionRepository, AcademicHistoryService academicHistoryService,
                          AuditService auditService) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.sectionRepository = sectionRepository;
        this.academicHistoryService = academicHistoryService;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 404 if the school or section does not exist, 409 if
     *                      {@code admissionNumber} is already taken.
     */
    @Transactional
    public Student create(CreateStudentRequest request) {
        String admissionNumber = request.admissionNumber().trim();

        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (request.sectionId() != null && !sectionRepository.existsById(request.sectionId())) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        if (studentRepository.existsByAdmissionNumber(admissionNumber)) {
            throw admissionConflict(admissionNumber);
        }

        Student student = new Student();
        student.setSchoolId(request.schoolId());
        student.setAdmissionNumber(admissionNumber);
        student.setAdmissionDate(request.admissionDate() != null ? request.admissionDate() : LocalDate.now());
        student.setSectionId(request.sectionId());
        student.setStatus(StudentStatus.ACTIVE);

        // --- personal information ---
        student.setFirstName(request.firstName().trim());
        student.setMiddleName(blankToNull(request.middleName()));
        student.setLastName(request.lastName().trim());
        student.setFullName(combineFullName(student.getFirstName(), student.getMiddleName(), student.getLastName()));
        student.setGender(request.gender());
        student.setDateOfBirth(request.dateOfBirth());
        student.setBloodGroup(request.bloodGroup());
        student.setNationality(blankToNull(request.nationality()));
        student.setReligion(blankToNull(request.religion()));
        student.setMotherTongue(blankToNull(request.motherTongue()));
        student.setCategory(blankToNull(request.category()));

        // --- admission information ---
        student.setRollNumber(blankToNull(request.rollNumber()));
        student.setEnrollmentNumber(blankToNull(request.enrollmentNumber()));
        student.setPreviousSchoolName(blankToNull(request.previousSchoolName()));
        student.setPreviousSchoolClass(blankToNull(request.previousSchoolClass()));
        student.setPreviousSchoolAdmissionNumber(blankToNull(request.previousSchoolAdmissionNumber()));
        student.setPreviousSchoolAddress(blankToNull(request.previousSchoolAddress()));
        student.setTransferCertificateNumber(blankToNull(request.transferCertificateNumber()));
        student.setAdmissionSource(blankToNull(request.admissionSource()));
        student.setRteStatus(Boolean.TRUE.equals(request.rteStatus()));
        student.setFamilyId(request.familyId());

        // --- guardian / parents ---
        student.setGuardianName(request.guardianName());
        student.setGuardianRelationship(request.guardianRelationship());
        student.setGuardianPhone(request.guardianPhone());
        student.setGuardianAlternatePhone(request.guardianAlternatePhone());
        student.setGuardianEmail(request.guardianEmail());
        student.setGuardianOccupation(blankToNull(request.guardianOccupation()));
        student.setGuardianContact(request.guardianContact() != null
                ? request.guardianContact()
                : combineContact(request.guardianPhone(), request.guardianEmail()));

        student.setFatherName(blankToNull(request.fatherName()));
        student.setFatherMobile(request.fatherMobile());
        student.setFatherEmail(request.fatherEmail());
        student.setFatherOccupation(blankToNull(request.fatherOccupation()));
        student.setMotherName(blankToNull(request.motherName()));
        student.setMotherMobile(request.motherMobile());
        student.setMotherEmail(request.motherEmail());
        student.setMotherOccupation(blankToNull(request.motherOccupation()));

        // --- emergency contact ---
        student.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        student.setEmergencyContactRelationship(request.emergencyContactRelationship());
        student.setEmergencyContactMobile(request.emergencyContactMobile());
        student.setEmergencyContactAlternateMobile(request.emergencyContactAlternateMobile());
        student.setEmergencyContactAddress(blankToNull(request.emergencyContactAddress()));

        // --- addresses ---
        student.setAddressLine1(blankToNull(request.addressLine1()));
        student.setAddressLine2(blankToNull(request.addressLine2()));
        student.setCity(blankToNull(request.city()));
        student.setState(blankToNull(request.state()));
        student.setCurrentCountry(blankToNull(request.currentCountry()));
        student.setPincode(blankToNull(request.pincode()));
        applyPermanentAddress(student, Boolean.TRUE.equals(request.permanentSameAsCurrentAddress()),
                request.permanentAddressLine1(), request.permanentAddressLine2(), request.permanentCity(),
                request.permanentState(), request.permanentCountry(), request.permanentPincode());

        // --- communication preferences ---
        student.setSmsNotificationsEnabled(request.smsNotificationsEnabled() == null || request.smsNotificationsEnabled());
        student.setWhatsappNotificationsEnabled(
                request.whatsappNotificationsEnabled() == null || request.whatsappNotificationsEnabled());
        student.setEmailNotificationsEnabled(
                request.emailNotificationsEnabled() == null || request.emailNotificationsEnabled());
        student.setPreferredLanguage(request.preferredLanguage() != null ? request.preferredLanguage() : "ENGLISH");

        Student saved;
        try {
            saved = studentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same admission number.
            throw admissionConflict(admissionNumber);
        }

        academicHistoryService.record(saved.getId(), saved.getSectionId(), AcademicChangeReason.ADMISSION);

        auditService.log(AuditActions.STUDENT_CREATED, AuditActions.STUDENT, saved.getId(),
                Map.of("admissionNumber", saved.getAdmissionNumber(), "fullName", saved.getFullName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Student> list(Pageable pageable) {
        return studentRepository.findAll(pageable);
    }

    /** Lists students in one section. Used as an optional filter on the list. */
    @Transactional(readOnly = true)
    public Page<Student> listBySection(UUID sectionId, Pageable pageable) {
        return studentRepository.findBySectionId(sectionId, pageable);
    }

    /**
     * Search/filter (plan Phase 3 section 4): {@code q} matches name, admission number, roll number,
     * enrollment number, guardian/father/mother name, or phone. Every other parameter is an exact-match
     * filter; null = unbounded, same convention as the enquiry/audit-log search.
     */
    @Transactional(readOnly = true)
    public Page<Student> search(String q, UUID classId, UUID sectionId, String category, String gender,
                                String status, Boolean rteStatus, Pageable pageable) {
        Specification<Student> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(root.get("admissionNumber")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("rollNumber"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("enrollmentNumber"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("guardianName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("fatherName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("motherName"), "")), like),
                        cb.like(cb.coalesce(root.get("guardianPhone"), ""), "%" + q.trim() + "%"),
                        cb.like(cb.coalesce(root.get("fatherMobile"), ""), "%" + q.trim() + "%"),
                        cb.like(cb.coalesce(root.get("motherMobile"), ""), "%" + q.trim() + "%")));
            }
            if (classId != null) {
                // section_id -> classes via the sections table isn't a direct column on students, so this
                // is filtered by section membership instead; the controller resolves classId to its
                // section ids before calling search when a bare classId filter is used without a section.
                predicates.add(root.get("sectionId").in(sectionIdsForClass(classId)));
            }
            if (sectionId != null) {
                predicates.add(cb.equal(root.get("sectionId"), sectionId));
            }
            if (category != null) {
                predicates.add(cb.equal(cb.lower(root.get("category")), category.toLowerCase(Locale.ROOT)));
            }
            if (gender != null) {
                predicates.add(cb.equal(root.get("gender"), gender));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (rteStatus != null) {
                predicates.add(cb.equal(root.get("rteStatus"), rteStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return studentRepository.findAll(spec, pageable);
    }

    private List<UUID> sectionIdsForClass(UUID classId) {
        return sectionRepository.findAllByOrderByName().stream()
                .filter(section -> classId.equals(section.getClassId()))
                .map(com.smsapp.academics.Section::getId)
                .toList();
    }

    /**
     * Lists students in one section for the attendance-marking roster.
     *
     * @throws ApiException 404 if the section does not exist.
     */
    @Transactional(readOnly = true)
    public Page<Student> listInSection(UUID sectionId, Pageable pageable) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        return studentRepository.findBySectionId(sectionId, pageable);
    }

    /**
     * Assigns (or reassigns) a student to a section. SCHOOL_ADMIN only.
     *
     * @throws ApiException 404 if the student or the section does not exist.
     */
    @Transactional
    public Student assignSection(UUID studentId, UUID sectionId) {
        Student student = requireStudent(studentId);
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        UUID previousSectionId = student.getSectionId();
        student.setSectionId(sectionId);
        Student saved = studentRepository.save(student);
        academicHistoryService.record(saved.getId(), sectionId, AcademicChangeReason.MANUAL_ASSIGNMENT);

        auditService.log(AuditActions.SECTION_ASSIGNED, AuditActions.STUDENT, studentId, details(
                "from", previousSectionId == null ? null : previousSectionId.toString(),
                "to", sectionId.toString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if no such student. A nonexistent id is reported as
     *         missing, never as forbidden, so the API does not leak whether the
     *         record exists (plan section 2 / 7d).
     */
    @Transactional(readOnly = true)
    public Student get(UUID id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }

    /** Every other student sharing this student's family group (plan Phase 3 section 6). Empty if unlinked. */
    @Transactional(readOnly = true)
    public List<Student> siblings(UUID studentId) {
        Student student = requireStudent(studentId);
        if (student.getFamilyId() == null) {
            return List.of();
        }
        return studentRepository.findByFamilyIdAndIdNotOrderByFullName(student.getFamilyId(), studentId);
    }

    /**
     * Links {@code studentId} into {@code siblingId}'s family group (creating a fresh group if the sibling
     * has none yet), so both students end up sharing one {@code family_id}.
     *
     * @throws ApiException 404 if either student does not exist, 400 if they're the same student.
     */
    @Transactional
    public Student linkSibling(UUID studentId, UUID siblingId) {
        if (studentId.equals(siblingId)) {
            throw new ApiException("A student cannot be linked as their own sibling", HttpStatus.BAD_REQUEST);
        }
        Student student = requireStudent(studentId);
        Student sibling = requireStudent(siblingId);

        UUID familyId = sibling.getFamilyId() != null ? sibling.getFamilyId()
                : student.getFamilyId() != null ? student.getFamilyId() : UUID.randomUUID();

        if (sibling.getFamilyId() == null) {
            sibling.setFamilyId(familyId);
            studentRepository.save(sibling);
        }
        student.setFamilyId(familyId);
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.STUDENT_SIBLING_LINKED, AuditActions.STUDENT, studentId,
                Map.of("siblingId", siblingId.toString(), "familyId", familyId.toString()));
        return saved;
    }

    /** Academic history: every recorded class/section placement, newest first (plan Phase 3 section 7). */
    @Transactional(readOnly = true)
    public List<AcademicHistoryRecord> academicHistory(UUID studentId) {
        requireStudent(studentId);
        return academicHistoryService.history(studentId);
    }

    /**
     * Updates a student's editable fields for a SCHOOL_ADMIN.
     *
     * <p>{@code schoolId} and {@code admissionNumber} are intentionally NOT editable here: the admission
     * number is the unique business key (UNIQUE(admission_number) since V18), so allowing a change would
     * re-open the duplicate-check / 409 path and risk rewriting a student's identity.
     *
     * @throws ApiException 404 if no such student, 400 if {@code status} is invalid.
     */
    @Transactional
    public Student update(UUID id, UpdateStudentRequest request) {
        Student student = requireStudent(id);
        Map<String, Object> before = editableSnapshot(student);

        student.setFirstName(request.firstName().trim());
        student.setMiddleName(blankToNull(request.middleName()));
        student.setLastName(request.lastName().trim());
        student.setFullName(combineFullName(student.getFirstName(), student.getMiddleName(), student.getLastName()));
        student.setGender(request.gender());
        if (request.dateOfBirth() != null) {
            student.setDateOfBirth(request.dateOfBirth());
        }
        student.setBloodGroup(request.bloodGroup());
        student.setNationality(blankToNull(request.nationality()));
        student.setReligion(blankToNull(request.religion()));
        student.setMotherTongue(blankToNull(request.motherTongue()));
        student.setCategory(blankToNull(request.category()));

        student.setRollNumber(blankToNull(request.rollNumber()));
        student.setEnrollmentNumber(blankToNull(request.enrollmentNumber()));
        if (request.admissionDate() != null) {
            student.setAdmissionDate(request.admissionDate());
        }
        student.setPreviousSchoolName(blankToNull(request.previousSchoolName()));
        student.setPreviousSchoolClass(blankToNull(request.previousSchoolClass()));
        student.setPreviousSchoolAdmissionNumber(blankToNull(request.previousSchoolAdmissionNumber()));
        student.setPreviousSchoolAddress(blankToNull(request.previousSchoolAddress()));
        student.setTransferCertificateNumber(blankToNull(request.transferCertificateNumber()));
        student.setAdmissionSource(blankToNull(request.admissionSource()));
        student.setRteStatus(Boolean.TRUE.equals(request.rteStatus()));
        student.setFamilyId(request.familyId());

        student.setGuardianName(blankToNull(request.guardianName()));
        student.setGuardianRelationship(request.guardianRelationship());
        student.setGuardianPhone(request.guardianPhone());
        student.setGuardianAlternatePhone(request.guardianAlternatePhone());
        student.setGuardianEmail(request.guardianEmail());
        student.setGuardianOccupation(blankToNull(request.guardianOccupation()));
        student.setGuardianContact(request.guardianContact() != null
                ? blankToNull(request.guardianContact())
                : combineContact(request.guardianPhone(), request.guardianEmail()));

        student.setFatherName(blankToNull(request.fatherName()));
        student.setFatherMobile(request.fatherMobile());
        student.setFatherEmail(request.fatherEmail());
        student.setFatherOccupation(blankToNull(request.fatherOccupation()));
        student.setMotherName(blankToNull(request.motherName()));
        student.setMotherMobile(request.motherMobile());
        student.setMotherEmail(request.motherEmail());
        student.setMotherOccupation(blankToNull(request.motherOccupation()));

        student.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        student.setEmergencyContactRelationship(request.emergencyContactRelationship());
        student.setEmergencyContactMobile(request.emergencyContactMobile());
        student.setEmergencyContactAlternateMobile(request.emergencyContactAlternateMobile());
        student.setEmergencyContactAddress(blankToNull(request.emergencyContactAddress()));

        student.setAddressLine1(blankToNull(request.addressLine1()));
        student.setAddressLine2(blankToNull(request.addressLine2()));
        student.setCity(blankToNull(request.city()));
        student.setState(blankToNull(request.state()));
        student.setCurrentCountry(blankToNull(request.currentCountry()));
        student.setPincode(blankToNull(request.pincode()));
        applyPermanentAddress(student, Boolean.TRUE.equals(request.permanentSameAsCurrentAddress()),
                request.permanentAddressLine1(), request.permanentAddressLine2(), request.permanentCity(),
                request.permanentState(), request.permanentCountry(), request.permanentPincode());

        if (request.smsNotificationsEnabled() != null) {
            student.setSmsNotificationsEnabled(request.smsNotificationsEnabled());
        }
        if (request.whatsappNotificationsEnabled() != null) {
            student.setWhatsappNotificationsEnabled(request.whatsappNotificationsEnabled());
        }
        if (request.emailNotificationsEnabled() != null) {
            student.setEmailNotificationsEnabled(request.emailNotificationsEnabled());
        }
        if (request.preferredLanguage() != null) {
            student.setPreferredLanguage(request.preferredLanguage());
        }
        student.setStatus(requireValidStatus(request.status()));

        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.STUDENT_UPDATED, AuditActions.STUDENT, id,
                Map.of("old", before, "new", editableSnapshot(saved)));
        return saved;
    }

    /** The editable fields of a student, for before/after audit context. */
    private static Map<String, Object> editableSnapshot(Student student) {
        return details(
                "fullName", student.getFullName(),
                "guardianName", student.getGuardianName(),
                "guardianContact", student.getGuardianContact(),
                "status", student.getStatus());
    }

    /**
     * Soft delete / reactivate: sets {@code status} without removing the row, so a
     * student is never physically deleted (plan Phase 3 section 8) -- financial,
     * attendance and exam history all stay intact.
     *
     * @throws ApiException 404 if no such student, 400 if {@code status} is not a recognized value.
     */
    @Transactional
    public Student changeStatus(UUID id, String status) {
        Student student = requireStudent(id);
        String previousStatus = student.getStatus();
        student.setStatus(requireValidStatus(status));
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.STUDENT_STATUS_CHANGED, AuditActions.STUDENT, id,
                details("from", previousStatus, "to", saved.getStatus()));
        return saved;
    }

    /** Sets or clears (with a null URL) the student's photo. */
    @Transactional
    public Student setPhotoUrl(UUID id, String photoUrl) {
        Student student = requireStudent(id);
        student.setPhotoUrl(photoUrl);
        Student saved = studentRepository.save(student);
        auditService.log(AuditActions.STUDENT_PHOTO_UPDATED, AuditActions.STUDENT, id, Map.of());
        return saved;
    }

    /**
     * Links this student to the parent/guardian user who can see them in the Parent Portal --
     * {@code guardianUserId} exists as a column and is read everywhere (e.g. the portal's own
     * "my children" lookup) but until online admissions (plan Phase 4.5 part 10) nothing ever set it.
     *
     * @throws ApiException 404 if no such student.
     */
    @Transactional
    public Student linkGuardianUser(UUID studentId, UUID guardianUserId) {
        Student student = requireStudent(studentId);
        student.setGuardianUserId(guardianUserId);
        Student saved = studentRepository.save(student);
        auditService.log(AuditActions.STUDENT_GUARDIAN_LINKED, AuditActions.STUDENT, studentId,
                Map.of("guardianUserId", guardianUserId.toString()));
        return saved;
    }

    private Student requireStudent(UUID id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }

    private static String requireValidStatus(String raw) {
        String status = StudentStatus.normalizeOrNull(raw);
        if (status == null) {
            throw new ApiException(StudentStatus.VALID_VALUES_MESSAGE, HttpStatus.BAD_REQUEST);
        }
        return status;
    }

    /** Joins whichever of phone/email is present into the legacy single-field format. */
    private static String combineContact(String phone, String email) {
        if (phone != null && email != null) {
            return phone + " · " + email;
        }
        return phone != null ? phone : email;
    }

    private static String combineFullName(String firstName, String middleName, String lastName) {
        // List.of(...) rejects null elements outright (middleName is commonly null) -- Stream.of is fine with them.
        return java.util.stream.Stream.of(firstName, middleName, lastName)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static void applyPermanentAddress(Student student, boolean sameAsCurrent, String line1, String line2,
                                              String city, String state, String country, String pincode) {
        if (sameAsCurrent) {
            student.setPermanentAddressLine1(student.getAddressLine1());
            student.setPermanentAddressLine2(student.getAddressLine2());
            student.setPermanentCity(student.getCity());
            student.setPermanentState(student.getState());
            student.setPermanentCountry(student.getCurrentCountry());
            student.setPermanentPincode(student.getPincode());
            return;
        }
        student.setPermanentAddressLine1(blankToNull(line1));
        student.setPermanentAddressLine2(blankToNull(line2));
        student.setPermanentCity(blankToNull(city));
        student.setPermanentState(blankToNull(state));
        student.setPermanentCountry(blankToNull(country));
        student.setPermanentPincode(blankToNull(pincode));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null-tolerant map builder ({@link Map#of} rejects null values). Keys/values alternate. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static ApiException admissionConflict(String admissionNumber) {
        return new ApiException(
                "A student with admission number '" + admissionNumber + "' already exists",
                HttpStatus.CONFLICT);
    }
}
