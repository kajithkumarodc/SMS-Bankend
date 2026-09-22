package com.smsapp.admission;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.admission.AdmissionDtos.AdmissionApplicationDetailResponse;
import com.smsapp.admission.AdmissionDtos.AdmissionApplicationDocumentResponse;
import com.smsapp.admission.AdmissionDtos.AdmissionApplicationSummaryResponse;
import com.smsapp.admission.AdmissionDtos.ApprovalResult;
import com.smsapp.admission.AdmissionDtos.StatusLookupResponse;
import com.smsapp.admission.AdmissionDtos.SubmitApplicationRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.email.EmailGateway;
import com.smsapp.student.Student;
import com.smsapp.student.StudentDocumentService;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentService;
import com.smsapp.user.Role;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.User;
import com.smsapp.user.UserActivationService;
import com.smsapp.user.UserRepository;
import com.smsapp.user.UserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the online-admission workflow end to end: public submission, admin review/status
 * transitions, and -- the critical integration (plan part 10) -- transactional, idempotent
 * conversion into a real {@link Student} + guardian {@link User} + portal relationship on approval.
 */
@Service
public class AdmissionApplicationService {

    private final AdmissionApplicationRepository applicationRepository;
    private final AdmissionCycleRepository cycleRepository;
    private final AdmissionApplicationDocumentService documentService;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserService userService;
    private final UserActivationService userActivationService;
    private final StudentService studentService;
    private final StudentDocumentService studentDocumentService;
    private final EmailGateway emailGateway;
    private final AuditService auditService;
    private final String frontendBaseUrl;

    public AdmissionApplicationService(AdmissionApplicationRepository applicationRepository,
                                        AdmissionCycleRepository cycleRepository,
                                        AdmissionApplicationDocumentService documentService,
                                        ClassRepository classRepository, UserRepository userRepository,
                                        RoleRepository roleRepository, UserService userService,
                                        UserActivationService userActivationService, StudentService studentService,
                                        StudentDocumentService studentDocumentService, EmailGateway emailGateway,
                                        AuditService auditService,
                                        @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.applicationRepository = applicationRepository;
        this.cycleRepository = cycleRepository;
        this.documentService = documentService;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userService = userService;
        this.userActivationService = userActivationService;
        this.studentService = studentService;
        this.studentDocumentService = studentDocumentService;
        this.emailGateway = emailGateway;
        this.auditService = auditService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    // --- Public submission -----------------------------------------------

    /** @throws ApiException 404 if the cycle doesn't exist or isn't open ("Online admissions are currently closed"). */
    @Transactional
    public AdmissionApplication submit(SubmitApplicationRequest request) {
        AdmissionCycle cycle = cycleRepository.findById(request.admissionCycleId())
                .filter(c -> AdmissionCycleStatus.OPEN.equals(c.getStatus()))
                .orElseThrow(() -> new ApiException("Online admissions are currently closed", HttpStatus.NOT_FOUND));

        AdmissionApplication application = new AdmissionApplication();
        application.setApplicationNumber(nextApplicationNumber());
        application.setAdmissionCycleId(cycle.getId());
        application.setSchoolId(cycle.getSchoolId());
        application.setStatus(AdmissionApplicationStatus.SUBMITTED);

        application.setFirstName(request.firstName().trim());
        application.setMiddleName(blankToNull(request.middleName()));
        application.setLastName(request.lastName().trim());
        application.setDateOfBirth(request.dateOfBirth());
        application.setGender(request.gender());
        application.setBloodGroup(request.bloodGroup());
        application.setNationality(blankToNull(request.nationality()));
        application.setReligion(blankToNull(request.religion()));
        application.setMotherTongue(blankToNull(request.motherTongue()));
        application.setCategory(blankToNull(request.category()));

        application.setApplyingClassId(request.applyingClassId());
        application.setPreviousSchoolName(blankToNull(request.previousSchoolName()));
        application.setPreviousSchoolClass(blankToNull(request.previousSchoolClass()));
        application.setPreviousSchoolAdmissionNumber(blankToNull(request.previousSchoolAdmissionNumber()));
        application.setPreviousSchoolAddress(blankToNull(request.previousSchoolAddress()));

        application.setGuardianName(blankToNull(request.guardianName()));
        application.setGuardianRelationship(request.guardianRelationship());
        application.setGuardianPhone(request.guardianPhone());
        application.setGuardianAlternatePhone(request.guardianAlternatePhone());
        application.setGuardianEmail(request.guardianEmail().trim().toLowerCase(Locale.ROOT));
        application.setGuardianOccupation(blankToNull(request.guardianOccupation()));
        application.setFatherName(blankToNull(request.fatherName()));
        application.setFatherMobile(request.fatherMobile());
        application.setFatherEmail(request.fatherEmail());
        application.setFatherOccupation(blankToNull(request.fatherOccupation()));
        application.setMotherName(blankToNull(request.motherName()));
        application.setMotherMobile(request.motherMobile());
        application.setMotherEmail(request.motherEmail());
        application.setMotherOccupation(blankToNull(request.motherOccupation()));

        application.setAddressLine1(blankToNull(request.addressLine1()));
        application.setAddressLine2(blankToNull(request.addressLine2()));
        application.setCity(blankToNull(request.city()));
        application.setState(blankToNull(request.state()));
        application.setCountry(blankToNull(request.country()));
        application.setPincode(blankToNull(request.pincode()));

        AdmissionApplication saved = applicationRepository.save(application);

        auditService.log(AuditActions.ADMISSION_APPLICATION_SUBMITTED, AuditActions.ADMISSION_APPLICATION,
                saved.getId(), Map.of("applicationNumber", saved.getApplicationNumber()));

        emailGateway.send(saved.getGuardianEmail(), "Application received - " + saved.getApplicationNumber(),
                "Thank you for applying. Your application has been received.\n\n"
                        + "Application reference: " + saved.getApplicationNumber() + "\n"
                        + "Please keep this reference number -- you will need it to check your application status.\n");

        return saved;
    }

    /** A real DB sequence (not {@code count(*) + 1}), so concurrent public submissions never collide. */
    private String nextApplicationNumber() {
        return String.format("APP-%06d", applicationRepository.nextApplicationNumberSeq());
    }

    /**
     * Public status lookup (plan part 15): reference + guardian email must match together. An
     * unmatched pair is reported identically to a nonexistent reference.
     */
    @Transactional(readOnly = true)
    public StatusLookupResponse statusLookup(String applicationNumber, String email) {
        AdmissionApplication application = applicationRepository
                .findByApplicationNumberAndGuardianEmailIgnoreCase(applicationNumber.trim(), email.trim())
                .orElseThrow(() -> new ApiException("Application not found", HttpStatus.NOT_FOUND));

        String cycleName = cycleRepository.findById(application.getAdmissionCycleId())
                .map(AdmissionCycle::getName).orElse(null);
        return new StatusLookupResponse(application.getApplicationNumber(), cycleName, application.getStatus(),
                application.getSubmittedAt(), application.getUpdatedAt(), statusMessage(application.getStatus()));
    }

    private static String statusMessage(String status) {
        return switch (status) {
            case AdmissionApplicationStatus.SUBMITTED -> "Your application has been received and is awaiting review.";
            case AdmissionApplicationStatus.UNDER_REVIEW -> "Your application is currently under review.";
            case AdmissionApplicationStatus.WAITLISTED -> "Your application has been waitlisted.";
            case AdmissionApplicationStatus.APPROVED -> "Congratulations -- your application has been approved.";
            case AdmissionApplicationStatus.REJECTED -> "Your application was not successful this time.";
            default -> "";
        };
    }

    /**
     * Verifies a reference+email pair for the public document-upload endpoint, the same identity
     * check as {@link #statusLookup} -- an application in a terminal/converted state can no longer
     * accept new documents.
     *
     * @throws ApiException 404 if the pair doesn't match, 409 if the application is already decided.
     */
    @Transactional(readOnly = true)
    public AdmissionApplication verifyPublicAccess(String applicationNumber, String email) {
        AdmissionApplication application = applicationRepository
                .findByApplicationNumberAndGuardianEmailIgnoreCase(applicationNumber.trim(), email.trim())
                .orElseThrow(() -> new ApiException("Application not found", HttpStatus.NOT_FOUND));
        if (List.of(AdmissionApplicationStatus.APPROVED, AdmissionApplicationStatus.REJECTED)
                .contains(application.getStatus())) {
            throw new ApiException("This application has already been decided and can no longer be changed",
                    HttpStatus.CONFLICT);
        }
        return application;
    }

    // --- Admin: list / detail --------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdmissionApplication> search(String q, String status, UUID admissionCycleId, UUID applyingClassId,
                                              LocalDate from, LocalDate to, Pageable pageable) {
        Specification<AdmissionApplication> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("applicationNumber")), like),
                        cb.like(cb.lower(root.get("firstName")), like),
                        cb.like(cb.lower(root.get("lastName")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("guardianName"), "")), like),
                        cb.like(cb.coalesce(root.get("guardianPhone"), ""), "%" + q.trim() + "%"),
                        cb.like(cb.lower(cb.coalesce(root.get("guardianEmail"), "")), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (admissionCycleId != null) {
                predicates.add(cb.equal(root.get("admissionCycleId"), admissionCycleId));
            }
            if (applyingClassId != null) {
                predicates.add(cb.equal(root.get("applyingClassId"), applyingClassId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("submittedAt"), to.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return applicationRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public AdmissionApplication get(UUID id) {
        return requireApplication(id);
    }

    @Transactional(readOnly = true)
    public AdmissionApplicationSummaryResponse toSummaryResponse(AdmissionApplication application) {
        return new AdmissionApplicationSummaryResponse(application.getId(), application.getApplicationNumber(),
                fullName(application), applyingClassName(application.getApplyingClassId()),
                application.getGuardianName(), application.getGuardianPhone(),
                cycleName(application.getAdmissionCycleId()), application.getSubmittedAt(), application.getStatus(),
                userFullName(application.getReviewedByUserId()), application.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public AdmissionApplicationDetailResponse toDetailResponse(AdmissionApplication a) {
        List<AdmissionApplicationDocumentResponse> documents = documentService.list(a.getId()).stream()
                .map(AdmissionApplicationDocumentResponse::from).toList();
        return new AdmissionApplicationDetailResponse(a.getId(), a.getApplicationNumber(), a.getAdmissionCycleId(),
                cycleName(a.getAdmissionCycleId()), a.getStatus(),
                a.getFirstName(), a.getMiddleName(), a.getLastName(), a.getDateOfBirth(), a.getGender(),
                a.getBloodGroup(), a.getNationality(), a.getReligion(), a.getMotherTongue(), a.getCategory(),
                a.getApplyingClassId(), applyingClassName(a.getApplyingClassId()), a.getPreviousSchoolName(),
                a.getPreviousSchoolClass(), a.getPreviousSchoolAdmissionNumber(), a.getPreviousSchoolAddress(),
                a.getAdmissionSource(),
                a.getGuardianName(), a.getGuardianRelationship(), a.getGuardianPhone(), a.getGuardianAlternatePhone(),
                a.getGuardianEmail(), a.getGuardianOccupation(), a.getFatherName(), a.getFatherMobile(),
                a.getFatherEmail(), a.getFatherOccupation(), a.getMotherName(), a.getMotherMobile(),
                a.getMotherEmail(), a.getMotherOccupation(),
                a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getState(), a.getCountry(), a.getPincode(),
                a.getReviewedAt(), userFullName(a.getReviewedByUserId()), a.getReviewerNotes(),
                a.getConvertedStudentId(), a.getConvertedGuardianUserId(),
                documents, a.getSubmittedAt(), a.getUpdatedAt());
    }

    // --- Admin: status transitions ----------------------------------------

    /** @throws ApiException 404 if no such application, 409 if the transition is not allowed. */
    @Transactional
    public AdmissionApplication startReview(UUID id, UUID actorUserId) {
        return transition(id, AdmissionApplicationStatus.UNDER_REVIEW, null, actorUserId);
    }

    @Transactional
    public AdmissionApplication reject(UUID id, String notes, UUID actorUserId) {
        AdmissionApplication saved = transition(id, AdmissionApplicationStatus.REJECTED, notes, actorUserId);
        emailGateway.send(saved.getGuardianEmail(), "Application update - " + saved.getApplicationNumber(),
                "Your application " + saved.getApplicationNumber() + " was not successful this time.\n"
                        + (notes != null ? "\nNote from the school: " + notes + "\n" : ""));
        return saved;
    }

    @Transactional
    public AdmissionApplication waitlist(UUID id, String notes, UUID actorUserId) {
        AdmissionApplication saved = transition(id, AdmissionApplicationStatus.WAITLISTED, notes, actorUserId);
        emailGateway.send(saved.getGuardianEmail(), "Application update - " + saved.getApplicationNumber(),
                "Your application " + saved.getApplicationNumber() + " has been placed on the waiting list.\n"
                        + (notes != null ? "\nNote from the school: " + notes + "\n" : ""));
        return saved;
    }

    /** Explicit, audited reopen back into review -- never a silent REJECTED/WAITLISTED -> APPROVED (plan part 9). */
    @Transactional
    public AdmissionApplication reopen(UUID id, String notes, UUID actorUserId) {
        return transition(id, AdmissionApplicationStatus.UNDER_REVIEW, notes, actorUserId);
    }

    private AdmissionApplication transition(UUID id, String toStatus, String notes, UUID actorUserId) {
        AdmissionApplication application = requireApplication(id);
        String fromStatus = application.getStatus();
        if (!AdmissionApplicationStatus.canTransition(fromStatus, toStatus)) {
            throw new ApiException("Cannot move an application from " + fromStatus + " to " + toStatus,
                    HttpStatus.CONFLICT);
        }
        application.setStatus(toStatus);
        application.setReviewedAt(OffsetDateTime.now());
        application.setReviewedByUserId(actorUserId);
        if (notes != null) {
            application.setReviewerNotes(blankToNull(notes));
        }
        AdmissionApplication saved = applicationRepository.save(application);

        auditService.log(AuditActions.ADMISSION_APPLICATION_STATUS_CHANGED, AuditActions.ADMISSION_APPLICATION, id,
                Map.of("from", fromStatus, "to", toStatus));
        return saved;
    }

    /**
     * Approval: the single most important integration (plan part 10/11/12). Runs in one transaction --
     * the student, the guardian user (new or reused), the portal link, the document adoption, and the
     * application's own converted/approved state all commit together or not at all.
     *
     * @throws ApiException 404 if no such application, 409 if it isn't awaiting a decision or was
     *         already approved (idempotency: a second approve() call never creates a second student).
     */
    @Transactional
    public ApprovalResult approve(UUID id, String notes, UUID actorUserId) {
        AdmissionApplication application = requireApplication(id);
        if (AdmissionApplicationStatus.APPROVED.equals(application.getStatus())) {
            throw new ApiException("This application has already been approved", HttpStatus.CONFLICT);
        }
        if (!AdmissionApplicationStatus.canTransition(application.getStatus(), AdmissionApplicationStatus.APPROVED)) {
            throw new ApiException("The application must be under review before it can be approved",
                    HttpStatus.CONFLICT);
        }

        String studentAdmissionNumber = "ADM-" + String.format("%06d", applicationRepository.nextStudentAdmissionNumberSeq());
        Student student = studentService.create(buildStudentRequest(application, studentAdmissionNumber));

        GuardianUser guardianUser = resolveGuardianUser(application);
        studentService.linkGuardianUser(student.getId(), guardianUser.userId());
        adoptDocuments(application, student.getId(), actorUserId);

        application.setStatus(AdmissionApplicationStatus.APPROVED);
        application.setReviewedAt(OffsetDateTime.now());
        application.setReviewedByUserId(actorUserId);
        application.setReviewerNotes(blankToNull(notes));
        application.setConvertedStudentId(student.getId());
        application.setConvertedGuardianUserId(guardianUser.userId());
        AdmissionApplication saved = applicationRepository.save(application);

        auditService.log(AuditActions.ADMISSION_APPLICATION_STATUS_CHANGED, AuditActions.ADMISSION_APPLICATION, id,
                Map.of("from", AdmissionApplicationStatus.UNDER_REVIEW, "to", AdmissionApplicationStatus.APPROVED));
        auditService.log(AuditActions.ADMISSION_APPLICATION_CONVERTED, AuditActions.ADMISSION_APPLICATION, id,
                Map.of("studentId", student.getId().toString(), "guardianUserId", guardianUser.userId().toString(),
                        "guardianUserCreated", guardianUser.created()));

        boolean invitationSent = sendApprovalEmail(saved, studentAdmissionNumber, guardianUser);

        return new ApprovalResult(toDetailResponse(saved), student.getId(), studentAdmissionNumber,
                guardianUser.userId(), guardianUser.created(), invitationSent);
    }

    private CreateStudentRequest buildStudentRequest(AdmissionApplication a, String admissionNumber) {
        return new CreateStudentRequest(
                a.getSchoolId(),
                a.getFirstName(), a.getMiddleName(), a.getLastName(), a.getGender(), a.getDateOfBirth(),
                a.getBloodGroup(), a.getNationality(), a.getReligion(), a.getMotherTongue(), a.getCategory(),
                admissionNumber, null, null, LocalDate.now(), null,
                a.getPreviousSchoolName(), a.getPreviousSchoolClass(), a.getPreviousSchoolAdmissionNumber(),
                a.getPreviousSchoolAddress(), null, a.getAdmissionSource(), null,
                a.getGuardianName(), a.getGuardianRelationship(), a.getGuardianPhone(), a.getGuardianAlternatePhone(),
                a.getGuardianEmail(), a.getGuardianOccupation(), null,
                a.getFatherName(), a.getFatherMobile(), a.getFatherEmail(), a.getFatherOccupation(),
                a.getMotherName(), a.getMotherMobile(), a.getMotherEmail(), a.getMotherOccupation(),
                null, null, null, null, null,
                a.getAddressLine1(), a.getAddressLine2(), a.getCity(), a.getState(), a.getCountry(), a.getPincode(),
                true, null, null, null, null, null, null,
                null,
                null, null, null, null);
    }

    private record GuardianUser(UUID userId, boolean created) {
    }

    /** Reuses an existing user by email if one exists (plan part 13); never a duplicate account. */
    private GuardianUser resolveGuardianUser(AdmissionApplication application) {
        String email = application.getGuardianEmail();
        return userRepository.findByEmail(email)
                .map(user -> new GuardianUser(user.getId(), false))
                .orElseGet(() -> {
                    Role parentRole = roleRepository.findByName(Roles.PARENT)
                            .orElseThrow(() -> new ApiException("PARENT role is not configured",
                                    HttpStatus.INTERNAL_SERVER_ERROR));
                    String guardianName = application.getGuardianName() != null && !application.getGuardianName().isBlank()
                            ? application.getGuardianName()
                            : "Parent/Guardian of " + fullName(application);
                    User user = userService.createGuardianAccount(email, guardianName, parentRole.getId());
                    return new GuardianUser(user.getId(), true);
                });
    }

    /** Copies each application document into the new student's own document storage (plan part 18). */
    private void adoptDocuments(AdmissionApplication application, UUID studentId, UUID actorUserId) {
        for (AdmissionApplicationDocument document : documentService.list(application.getId())) {
            var sourcePath = documentService.resolvePath(application.getId(), document);
            if (!java.nio.file.Files.isReadable(sourcePath)) {
                continue;
            }
            studentDocumentService.adoptApplicationDocument(studentId, sourcePath, document.getDocumentType(),
                    document.getOriginalFilename(), document.getContentType(), document.getFileSizeBytes(), actorUserId);
        }
    }

    /** @return whether a portal-invitation activation link was included (i.e. a brand-new account was created). */
    private boolean sendApprovalEmail(AdmissionApplication application, String studentAdmissionNumber,
                                       GuardianUser guardianUser) {
        StringBuilder body = new StringBuilder();
        body.append("Congratulations! The application ").append(application.getApplicationNumber())
                .append(" for ").append(fullName(application)).append(" has been approved.\n\n")
                .append("Student admission number: ").append(studentAdmissionNumber).append("\n");

        boolean invitationSent = false;
        if (guardianUser.created()) {
            String token = userActivationService.issueToken(guardianUser.userId());
            String activationLink = frontendBaseUrl + "/activate?token=" + token;
            body.append("\nA parent portal account has been created for you (").append(application.getGuardianEmail())
                    .append(").\nSet your password to activate it: ").append(activationLink)
                    .append("\nThis link expires in 72 hours.\n");
            invitationSent = true;
        } else {
            body.append("\nYou can view this student from your existing parent portal account.\n");
        }

        emailGateway.send(application.getGuardianEmail(), "Application approved - " + application.getApplicationNumber(),
                body.toString());
        return invitationSent;
    }

    // --- Enquiry linking (plan part 17) -----------------------------------

    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        requireApplication(id);
    }

    // --- Lookups -----------------------------------------------------------

    private static String fullName(AdmissionApplication a) {
        return java.util.stream.Stream.of(a.getFirstName(), a.getMiddleName(), a.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .reduce((x, y) -> x + " " + y).orElse("");
    }

    private String applyingClassName(UUID classId) {
        if (classId == null) {
            return null;
        }
        return classRepository.findById(classId).map(SchoolClass::getName).orElse(null);
    }

    private String cycleName(UUID cycleId) {
        return cycleRepository.findById(cycleId).map(AdmissionCycle::getName).orElse(null);
    }

    private String userFullName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    private AdmissionApplication requireApplication(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ApiException("Application not found", HttpStatus.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
