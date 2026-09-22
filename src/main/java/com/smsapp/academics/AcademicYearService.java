package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.CreateAcademicYearRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.AcademicChangeReason;
import com.smsapp.student.AcademicHistoryService;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.student.StudentStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Academic sessions/years + student promotion between sections (plan:
 * "Academic Session -> Class -> Section -> Subject -> Teacher -> Students...
 * support student promotion between academic sessions"). Promotion here is
 * section-to-section (the concrete, database-backed operation every
 * "promote to next year" action reduces to) rather than a session-wide batch
 * job -- it reuses the same {@code students.section_id} reassignment
 * {@link com.smsapp.student.StudentService#assignSection} already performs
 * one student at a time, just batched with one audit entry and, since Phase 4,
 * real per-student validation (active status, no duplicate promotion into the
 * same session, class/section coherency) instead of a silent best-effort move.
 */
@Service
public class AcademicYearService {

    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;
    private final StudentRepository studentRepository;
    private final AcademicHistoryService academicHistoryService;
    private final AuditService auditService;

    public AcademicYearService(AcademicYearRepository academicYearRepository, SectionRepository sectionRepository,
                               StudentRepository studentRepository, AcademicHistoryService academicHistoryService,
                               AuditService auditService) {
        this.academicYearRepository = academicYearRepository;
        this.sectionRepository = sectionRepository;
        this.studentRepository = studentRepository;
        this.academicHistoryService = academicHistoryService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AcademicYear> listAll() {
        return academicYearRepository.findAllByOrderByStartDateDesc();
    }

    /** Whichever academic year is currently marked "current", if any -- readable by any authenticated role. */
    @Transactional(readOnly = true)
    public Optional<AcademicYear> current() {
        return academicYearRepository.findByCurrentTrue();
    }

    /**
     * @throws ApiException 409 if a year with that name already exists, 400 if
     *         {@code endDate} is not after {@code startDate}.
     */
    @Transactional
    public AcademicYear create(CreateAcademicYearRequest request) {
        String name = request.name().trim();
        if (!request.endDate().isAfter(request.startDate())) {
            throw new ApiException("End date must be after the start date", HttpStatus.BAD_REQUEST);
        }
        if (academicYearRepository.existsByName(name)) {
            throw new ApiException("An academic year named '" + name + "' already exists", HttpStatus.CONFLICT);
        }

        AcademicYear year = new AcademicYear();
        year.setName(name);
        year.setStartDate(request.startDate());
        year.setEndDate(request.endDate());
        year.setCurrent(false);

        AcademicYear saved;
        try {
            saved = academicYearRepository.saveAndFlush(year);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("An academic year named '" + name + "' already exists", HttpStatus.CONFLICT);
        }

        auditService.log(AuditActions.ACADEMIC_YEAR_CREATED, AuditActions.ACADEMIC_YEAR, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }

    /** Marks one academic year current, unmarking whichever one previously was. 404 if it doesn't exist. */
    @Transactional
    public AcademicYear setCurrent(UUID id) {
        AcademicYear year = academicYearRepository.findById(id)
                .orElseThrow(() -> new ApiException("Academic year not found", HttpStatus.NOT_FOUND));

        academicYearRepository.findByCurrentTrue().ifPresent(previous -> {
            previous.setCurrent(false);
            academicYearRepository.save(previous);
        });
        year.setCurrent(true);
        AcademicYear saved = academicYearRepository.save(year);

        auditService.log(AuditActions.ACADEMIC_YEAR_SET_CURRENT, AuditActions.ACADEMIC_YEAR, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }

    /** One promoted-or-not outcome per requested student id, in the order requested. */
    public record PromotionResult(UUID studentId, String studentName, boolean promoted, String reason) {
    }

    public record PromotionOutcome(List<PromotionResult> results, int promotedCount) {
    }

    /**
     * Bulk-moves students from one section to another (e.g. promoting a class to the next grade),
     * recording a new append-only {@code student_academic_history} row per student actually moved
     * (plan Phase 3 section 7: never overwrite -- the row they're promoted FROM stays visible).
     *
     * <p>Every requested student id gets its own {@link PromotionResult}, even when it is skipped --
     * "success/failure results per student" (plan Phase 4 section A). A student is skipped, never a
     * hard failure, when: not currently in {@code fromSectionId} (stale roster snapshot), not ACTIVE
     * (archived/graduated/transferred/left students are never promoted), or already has a placement
     * recorded for the target academic year (duplicate promotion / same-session guard). The whole
     * batch is one transaction -- a genuine error (e.g. a constraint violation) rolls back every
     * student's move, never a partial promotion.
     *
     * @throws ApiException 404 if either section (or an explicit {@code toClassId}/{@code targetAcademicYearId})
     *         does not exist, 400 if {@code fromSectionId} equals {@code toSectionId} or {@code toSectionId}
     *         does not belong to {@code toClassId}.
     */
    @Transactional
    public PromotionOutcome promoteStudents(UUID fromSectionId, UUID toSectionId, UUID toClassId,
                                            UUID targetAcademicYearId, Set<UUID> studentIds) {
        if (fromSectionId.equals(toSectionId)) {
            throw new ApiException("Source and destination section must be different", HttpStatus.BAD_REQUEST);
        }
        if (!sectionRepository.existsById(fromSectionId)) {
            throw new ApiException("Source section not found", HttpStatus.NOT_FOUND);
        }
        Section toSection = sectionRepository.findById(toSectionId)
                .orElseThrow(() -> new ApiException("Destination section not found", HttpStatus.NOT_FOUND));
        if (toClassId != null && !toClassId.equals(toSection.getClassId())) {
            throw new ApiException("Destination section does not belong to the destination class", HttpStatus.BAD_REQUEST);
        }

        UUID effectiveYearId;
        if (targetAcademicYearId != null) {
            if (!academicYearRepository.existsById(targetAcademicYearId)) {
                throw new ApiException("Target academic year not found", HttpStatus.NOT_FOUND);
            }
            effectiveYearId = targetAcademicYearId;
        } else {
            effectiveYearId = academicYearRepository.findByCurrentTrue().map(AcademicYear::getId).orElse(null);
        }

        Map<UUID, Student> byId = new HashMap<>();
        studentRepository.findAllById(studentIds).forEach(s -> byId.put(s.getId(), s));

        List<PromotionResult> results = new ArrayList<>();
        List<Student> toMove = new ArrayList<>();
        for (UUID studentId : studentIds) {
            Student student = byId.get(studentId);
            if (student == null) {
                results.add(new PromotionResult(studentId, null, false, "Student not found"));
                continue;
            }
            if (!fromSectionId.equals(student.getSectionId())) {
                results.add(new PromotionResult(studentId, student.getFullName(), false,
                        "Not currently in the source section"));
                continue;
            }
            if (!StudentStatus.ACTIVE.equals(student.getStatus())) {
                results.add(new PromotionResult(studentId, student.getFullName(), false,
                        "Student is not active (" + student.getStatus() + ")"));
                continue;
            }
            if (effectiveYearId != null
                    && academicHistoryService.alreadyPlacedForYear(studentId, effectiveYearId)) {
                results.add(new PromotionResult(studentId, student.getFullName(), false,
                        "Already promoted for this academic session"));
                continue;
            }
            student.setSectionId(toSectionId);
            toMove.add(student);
            results.add(new PromotionResult(studentId, student.getFullName(), true, null));
        }

        studentRepository.saveAll(toMove);
        toMove.forEach(s -> academicHistoryService.recordForYear(
                s.getId(), toSectionId, AcademicChangeReason.PROMOTION, effectiveYearId));

        auditService.log(AuditActions.STUDENTS_PROMOTED, AuditActions.SECTION, toSectionId,
                Map.of("fromSectionId", fromSectionId.toString(), "toSectionId", toSectionId.toString(),
                        "studentCount", toMove.size(), "requestedCount", studentIds.size()));
        return new PromotionOutcome(results, toMove.size());
    }

    /** Promotion History screen (plan Phase 4 section A) -- actual promotions only, newest first, with before/after placement. */
    @Transactional(readOnly = true)
    public Page<AcademicsDtos.PromotionHistoryEntry> promotionHistory(Pageable pageable) {
        return academicHistoryService.promotionHistory(pageable).map(record -> {
            com.smsapp.student.AcademicHistoryRecord previous = academicHistoryService.previousFor(record);
            String studentName = studentRepository.findById(record.getStudentId())
                    .map(Student::getFullName)
                    .orElse(null);
            return new AcademicsDtos.PromotionHistoryEntry(
                    record.getStudentId(), studentName,
                    previous != null ? previous.getAcademicYearId() : null,
                    previous != null ? previous.getClassId() : null,
                    previous != null ? previous.getSectionId() : null,
                    record.getAcademicYearId(), record.getClassId(), record.getSectionId(),
                    record.getRecordedAt(), record.getRecordedByUserId());
        });
    }
}
