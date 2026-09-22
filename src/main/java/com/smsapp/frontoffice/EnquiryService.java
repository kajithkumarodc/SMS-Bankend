package com.smsapp.frontoffice;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.EnquiryDtos.AssignableStaffResponse;
import com.smsapp.frontoffice.EnquiryDtos.CreateEnquiryRequest;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryConversionResult;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryResponse;
import com.smsapp.frontoffice.EnquiryDtos.EnquirySummaryResponse;
import com.smsapp.frontoffice.EnquiryDtos.FollowUpResponse;
import com.smsapp.frontoffice.EnquiryDtos.RecordFollowUpRequest;
import com.smsapp.frontoffice.EnquiryDtos.UpdateEnquiryRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.student.StudentService;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Front Office / Admission Enquiry (Phase 2). Convert-to-student reuses
 * {@link StudentService#create} verbatim -- the enquiry only ever supplies the
 * lead fields it already has; the operator fills in the rest (admission number,
 * date of birth, ...) on the same admission form/validation the Students module
 * already has, so there is exactly one place a student row gets created.
 */
@Service
public class EnquiryService {

    private final AdmissionEnquiryRepository enquiryRepository;
    private final EnquiryFollowUpRepository followUpRepository;
    private final EnquirySourceRepository sourceRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final StudentService studentService;
    private final AuditService auditService;

    public EnquiryService(AdmissionEnquiryRepository enquiryRepository, EnquiryFollowUpRepository followUpRepository,
                          EnquirySourceRepository sourceRepository, ClassRepository classRepository,
                          UserRepository userRepository, StudentService studentService, AuditService auditService) {
        this.enquiryRepository = enquiryRepository;
        this.followUpRepository = followUpRepository;
        this.sourceRepository = sourceRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.studentService = studentService;
        this.auditService = auditService;
    }

    /** @throws ApiException 404 if {@code classId}/{@code sourceId}/{@code assignedStaffUserId} is given but unknown. */
    @Transactional
    public AdmissionEnquiry create(CreateEnquiryRequest request) {
        requireReferencesExist(request.classId(), request.sourceId(), request.assignedStaffUserId());

        AdmissionEnquiry enquiry = new AdmissionEnquiry();
        enquiry.setEnquiryNumber(nextEnquiryNumber());
        enquiry.setApplicantName(request.applicantName().trim());
        enquiry.setGuardianName(blankToNull(request.guardianName()));
        enquiry.setPhone(blankToNull(request.phone()));
        enquiry.setEmail(blankToNull(request.email()));
        enquiry.setClassId(request.classId());
        enquiry.setEnquiryDate(request.enquiryDate() != null ? request.enquiryDate() : LocalDate.now());
        enquiry.setSourceId(request.sourceId());
        enquiry.setAssignedStaffUserId(request.assignedStaffUserId());
        enquiry.setRemarks(blankToNull(request.remarks()));
        enquiry.setStatus(EnquiryStatus.ACTIVE);
        enquiry.setArchived(false);

        AdmissionEnquiry saved = enquiryRepository.save(enquiry);
        auditService.log(AuditActions.ENQUIRY_CREATED, AuditActions.ENQUIRY, saved.getId(),
                Map.of("enquiryNumber", saved.getEnquiryNumber(), "applicantName", saved.getApplicantName()));
        return saved;
    }

    /** A real DB sequence (not {@code count(*) + 1}) so concurrent creates never collide. */
    private String nextEnquiryNumber() {
        return String.format("ENQ-%06d", enquiryRepository.nextEnquiryNumberSeq());
    }

    @Transactional(readOnly = true)
    public AdmissionEnquiry get(UUID id) {
        return requireEnquiry(id);
    }

    /**
     * @throws ApiException 404 if no such enquiry, or if a referenced class/source/staff id is unknown.
     */
    @Transactional
    public AdmissionEnquiry update(UUID id, UpdateEnquiryRequest request) {
        AdmissionEnquiry enquiry = requireEnquiry(id);
        requireReferencesExist(request.classId(), request.sourceId(), request.assignedStaffUserId());

        enquiry.setApplicantName(request.applicantName().trim());
        enquiry.setGuardianName(blankToNull(request.guardianName()));
        enquiry.setPhone(blankToNull(request.phone()));
        enquiry.setEmail(blankToNull(request.email()));
        enquiry.setClassId(request.classId());
        enquiry.setSourceId(request.sourceId());
        enquiry.setAssignedStaffUserId(request.assignedStaffUserId());
        enquiry.setRemarks(blankToNull(request.remarks()));
        AdmissionEnquiry saved = enquiryRepository.save(enquiry);

        auditService.log(AuditActions.ENQUIRY_UPDATED, AuditActions.ENQUIRY, id,
                Map.of("enquiryNumber", saved.getEnquiryNumber()));
        return saved;
    }

    /** @throws ApiException 404 if no such enquiry, 400 if {@code status} is not a recognized value. */
    @Transactional
    public AdmissionEnquiry changeStatus(UUID id, String status) {
        AdmissionEnquiry enquiry = requireEnquiry(id);
        String normalized = EnquiryStatus.normalizeOrNull(status);
        if (normalized == null) {
            throw new ApiException("Status must be one of ACTIVE, FOLLOW_UP, WON, PASSIVE, LOST, DEAD",
                    HttpStatus.BAD_REQUEST);
        }
        String previous = enquiry.getStatus();
        enquiry.setStatus(normalized);
        AdmissionEnquiry saved = enquiryRepository.save(enquiry);

        auditService.log(AuditActions.ENQUIRY_STATUS_CHANGED, AuditActions.ENQUIRY, id,
                Map.of("from", previous, "to", normalized));
        return saved;
    }

    /** Archive (soft delete) or restore an enquiry. Follow-up history is never deleted. */
    @Transactional
    public AdmissionEnquiry setArchived(UUID id, boolean archived) {
        AdmissionEnquiry enquiry = requireEnquiry(id);
        enquiry.setArchived(archived);
        AdmissionEnquiry saved = enquiryRepository.save(enquiry);

        auditService.log(AuditActions.ENQUIRY_ARCHIVED, AuditActions.ENQUIRY, id, Map.of("archived", archived));
        return saved;
    }

    /**
     * Search with optional filters -- every parameter is {@code null} = unbounded, same convention as
     * {@code AuditLogService#search}. Archived enquiries are excluded unless {@code includeArchived} is true.
     */
    @Transactional(readOnly = true)
    public Page<AdmissionEnquiry> search(String query, String status, UUID sourceId, UUID classId,
                                         UUID assignedStaffUserId, LocalDate from, LocalDate to,
                                         boolean includeArchived, Pageable pageable) {
        Specification<AdmissionEnquiry> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!includeArchived) {
                predicates.add(cb.isFalse(root.get("archived")));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("applicantName")), like),
                        cb.like(cb.lower(root.get("guardianName")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("enquiryNumber")), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (sourceId != null) {
                predicates.add(cb.equal(root.get("sourceId"), sourceId));
            }
            if (classId != null) {
                predicates.add(cb.equal(root.get("classId"), classId));
            }
            if (assignedStaffUserId != null) {
                predicates.add(cb.equal(root.get("assignedStaffUserId"), assignedStaffUserId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("enquiryDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("enquiryDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return enquiryRepository.findAll(spec, pageable);
    }

    // --- Follow-ups --------------------------------------------------

    /** @throws ApiException 404 if no such enquiry, 400 if {@code followUpType} is not recognized. */
    @Transactional
    public EnquiryFollowUp recordFollowUp(UUID enquiryId, RecordFollowUpRequest request, UUID actingStaffUserId) {
        AdmissionEnquiry enquiry = requireEnquiry(enquiryId);
        String type = normalizeFollowUpTypeOrNull(request.followUpType());
        if (type == null) {
            throw new ApiException("Follow-up type must be one of CALL, EMAIL, SMS, WHATSAPP, VISIT, OTHER",
                    HttpStatus.BAD_REQUEST);
        }

        EnquiryFollowUp followUp = new EnquiryFollowUp();
        followUp.setEnquiryId(enquiryId);
        followUp.setFollowUpDate(request.followUpDate());
        followUp.setFollowUpType(type);
        followUp.setNotes(blankToNull(request.notes()));
        followUp.setStaffUserId(actingStaffUserId);
        followUp.setNextFollowUpDate(request.nextFollowUpDate());
        EnquiryFollowUp saved = followUpRepository.save(followUp);

        // Keep the enquiry's denormalized "latest follow-up" cache in sync, and move
        // a still-open lead into FOLLOW_UP so the pipeline reflects that contact was made.
        enquiry.setFollowUpDate(request.nextFollowUpDate());
        enquiry.setFollowUpNotes(blankToNull(request.notes()));
        if (EnquiryStatus.ACTIVE.equals(enquiry.getStatus())) {
            enquiry.setStatus(EnquiryStatus.FOLLOW_UP);
        }
        enquiryRepository.save(enquiry);

        auditService.log(AuditActions.ENQUIRY_FOLLOWUP_RECORDED, AuditActions.ENQUIRY_FOLLOW_UP, saved.getId(),
                Map.of("enquiryId", enquiryId.toString(), "followUpType", type));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EnquiryFollowUp> listFollowUps(UUID enquiryId) {
        requireEnquiry(enquiryId);
        return followUpRepository.findByEnquiryIdOrderByFollowUpDateDescCreatedAtDesc(enquiryId);
    }

    // --- Convert to student -------------------------------------------

    /**
     * Converts an enquiry into a real student admission, reusing {@link StudentService#create} as-is
     * (same validation, same 409-on-duplicate-admission-number, same audit trail).
     *
     * @throws ApiException 404 if the enquiry doesn't exist, 409 if it was already converted (this is what
     *         makes converting the same enquiry twice impossible -- never a duplicate student).
     */
    @Transactional
    public EnquiryConversionResult convertToStudent(UUID enquiryId, CreateStudentRequest request) {
        AdmissionEnquiry enquiry = requireEnquiry(enquiryId);
        if (enquiry.getConvertedStudentId() != null) {
            throw new ApiException("This enquiry was already converted to a student", HttpStatus.CONFLICT);
        }

        Student student = studentService.create(request);

        enquiry.setConvertedStudentId(student.getId());
        enquiry.setStatus(EnquiryStatus.WON);
        AdmissionEnquiry savedEnquiry = enquiryRepository.save(enquiry);

        auditService.log(AuditActions.ENQUIRY_CONVERTED, AuditActions.ENQUIRY, enquiryId,
                Map.of("studentId", student.getId().toString(), "enquiryNumber", enquiry.getEnquiryNumber()));

        return new EnquiryConversionResult(StudentResponse.from(student), toResponse(savedEnquiry));
    }

    /**
     * Links this enquiry to the online application it turned into (plan Phase 4.5 part 17) -- informational
     * only, the same "set once, never enforced again" shape as {@code convertedStudentId} itself. Does not
     * touch the enquiry's status or trigger conversion; that still happens through the application's own
     * approval flow.
     *
     * @throws ApiException 404 if no such enquiry.
     */
    @Transactional
    public AdmissionEnquiry linkApplication(UUID enquiryId, UUID applicationId) {
        AdmissionEnquiry enquiry = requireEnquiry(enquiryId);
        enquiry.setAdmissionApplicationId(applicationId);
        AdmissionEnquiry saved = enquiryRepository.save(enquiry);
        auditService.log(AuditActions.ADMISSION_ENQUIRY_LINKED, AuditActions.ENQUIRY, enquiryId,
                Map.of("applicationId", applicationId.toString()));
        return saved;
    }

    // --- Dashboard summary ---------------------------------------------

    /** Real database counts for the Front Office overview -- nothing hardcoded. */
    @Transactional(readOnly = true)
    public EnquirySummaryResponse summary() {
        long total = enquiryRepository.countByArchivedFalse();
        long active = enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.ACTIVE)
                + enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.FOLLOW_UP);
        long followUpsDue = enquiryRepository.countByFollowUpDateLessThanEqualAndArchivedFalseAndStatusNotIn(
                LocalDate.now(), EnquiryStatus.CLOSED);
        long converted = enquiryRepository.countByConvertedStudentIdIsNotNull();
        long lost = enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.LOST);

        Map<UUID, String> sourceNames = sourceRepository.findAll().stream()
                .collect(Collectors.toMap(EnquirySource::getId, EnquirySource::getName));
        Map<UUID, String> classNames = classRepository.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, SchoolClass::getName));

        Map<String, Long> bySource = new HashMap<>();
        for (AdmissionEnquiryRepository.SourceCount row : enquiryRepository.countBySource()) {
            String label = row.getSourceId() == null ? "Unspecified" : sourceNames.getOrDefault(row.getSourceId(), "Unknown");
            bySource.merge(label, row.getTotal(), Long::sum);
        }
        Map<String, Long> byClass = new HashMap<>();
        for (AdmissionEnquiryRepository.ClassCount row : enquiryRepository.countByClass()) {
            String label = row.getClassId() == null ? "Unspecified" : classNames.getOrDefault(row.getClassId(), "Unknown");
            byClass.merge(label, row.getTotal(), Long::sum);
        }

        List<EnquiryResponse> recent = enquiryRepository.findTop5ByArchivedFalseOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();

        return new EnquirySummaryResponse(total, active, followUpsDue, converted, lost, bySource, byClass, recent);
    }

    // --- Response mapping / lookups -------------------------------------

    @Transactional(readOnly = true)
    public EnquiryResponse toResponse(AdmissionEnquiry enquiry) {
        String sourceName = enquiry.getSourceId() == null ? null
                : sourceRepository.findById(enquiry.getSourceId()).map(EnquirySource::getName).orElse(null);
        String staffName = enquiry.getAssignedStaffUserId() == null ? null
                : userRepository.findById(enquiry.getAssignedStaffUserId()).map(User::getFullName).orElse(null);
        return EnquiryResponse.from(enquiry, sourceName, staffName);
    }

    @Transactional(readOnly = true)
    public FollowUpResponse toResponse(EnquiryFollowUp followUp) {
        String staffName = followUp.getStaffUserId() == null ? null
                : userRepository.findById(followUp.getStaffUserId()).map(User::getFullName).orElse(null);
        return FollowUpResponse.from(followUp, staffName);
    }

    /** Users who can be assigned an enquiry -- any account, same leniency as the existing staff picker. */
    @Transactional(readOnly = true)
    public List<AssignableStaffResponse> assignableStaff() {
        return userRepository.findAllByOrderByFullName().stream()
                .map(u -> new AssignableStaffResponse(u.getId(), u.getFullName(), u.getEmail()))
                .toList();
    }

    private void requireReferencesExist(UUID classId, UUID sourceId, UUID assignedStaffUserId) {
        if (classId != null && !classRepository.existsById(classId)) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        if (sourceId != null && !sourceRepository.existsById(sourceId)) {
            throw new ApiException("Enquiry source not found", HttpStatus.NOT_FOUND);
        }
        if (assignedStaffUserId != null && !userRepository.existsById(assignedStaffUserId)) {
            throw new ApiException("Assigned staff user not found", HttpStatus.NOT_FOUND);
        }
    }

    private AdmissionEnquiry requireEnquiry(UUID id) {
        return enquiryRepository.findById(id)
                .orElseThrow(() -> new ApiException("Enquiry not found", HttpStatus.NOT_FOUND));
    }

    private static String normalizeFollowUpTypeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("CALL", "EMAIL", "SMS", "WHATSAPP", "VISIT", "OTHER").contains(upper) ? upper : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
