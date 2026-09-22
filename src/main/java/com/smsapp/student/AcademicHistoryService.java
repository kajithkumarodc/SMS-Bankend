package com.smsapp.student;

import com.smsapp.academics.AcademicYearRepository;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records "this student was in this class/section during this academic year" (plan Phase 3 section 7,
 * extended in Phase 4 with who/why -- see {@link AcademicChangeReason}). Called whenever a student's
 * section actually changes -- initial admission ({@link StudentService#create}), a manual reassignment
 * ({@link StudentService#assignSection}), or a year-end promotion
 * ({@code AcademicYearService#promoteStudents}) -- never on its own as an edit. Each call appends a new
 * row; nothing here is ever updated or deleted, so historical placements survive however many times a
 * student is later moved.
 */
@Service
public class AcademicHistoryService {

    private final AcademicHistoryRepository academicHistoryRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;

    public AcademicHistoryService(AcademicHistoryRepository academicHistoryRepository,
                                  AcademicYearRepository academicYearRepository,
                                  SectionRepository sectionRepository) {
        this.academicHistoryRepository = academicHistoryRepository;
        this.academicYearRepository = academicYearRepository;
        this.sectionRepository = sectionRepository;
    }

    /** Records against whichever academic year is currently marked "current" (may be none). No-op when {@code sectionId} is null. */
    @Transactional
    public void record(UUID studentId, UUID sectionId, String changeReason) {
        UUID currentYearId = academicYearRepository.findByCurrentTrue().map(com.smsapp.academics.AcademicYear::getId).orElse(null);
        recordForYear(studentId, sectionId, changeReason, currentYearId);
    }

    /** Records against an explicit academic year (promotion to a specific target session). No-op when {@code sectionId} is null. */
    @Transactional
    public void recordForYear(UUID studentId, UUID sectionId, String changeReason, UUID academicYearId) {
        if (sectionId == null) {
            return;
        }
        UUID classId = sectionRepository.findById(sectionId).map(Section::getClassId).orElse(null);

        AcademicHistoryRecord record = new AcademicHistoryRecord();
        record.setStudentId(studentId);
        record.setAcademicYearId(academicYearId);
        record.setClassId(classId);
        record.setSectionId(sectionId);
        record.setChangeReason(changeReason);
        record.setRecordedByUserId(currentActorId());
        academicHistoryRepository.save(record);
    }

    /** Duplicate-promotion guard: has this student already been promoted into this academic year? */
    @Transactional(readOnly = true)
    public boolean alreadyPlacedForYear(UUID studentId, UUID academicYearId) {
        return academicHistoryRepository.existsByStudentIdAndAcademicYearIdAndChangeReason(
                studentId, academicYearId, AcademicChangeReason.PROMOTION);
    }

    @Transactional(readOnly = true)
    public java.util.List<AcademicHistoryRecord> history(UUID studentId) {
        return academicHistoryRepository.findByStudentIdOrderByRecordedAtDesc(studentId);
    }

    /** Promotion History screen: every placement change school-wide, newest first. */
    @Transactional(readOnly = true)
    public Page<AcademicHistoryRecord> allHistory(Pageable pageable) {
        return academicHistoryRepository.findAllByOrderByRecordedAtDesc(pageable);
    }

    /** Promotion History screen (plan Phase 4 section A) -- actual promotions only, newest first. */
    @Transactional(readOnly = true)
    public Page<AcademicHistoryRecord> promotionHistory(Pageable pageable) {
        return academicHistoryRepository.findByChangeReasonOrderByRecordedAtDesc(AcademicChangeReason.PROMOTION, pageable);
    }

    /** The history row immediately before {@code record} for the same student, if any -- its "previous" placement. */
    @Transactional(readOnly = true)
    public AcademicHistoryRecord previousFor(AcademicHistoryRecord record) {
        return history(record.getStudentId()).stream()
                .filter(r -> r.getRecordedAt().isBefore(record.getRecordedAt()))
                .findFirst()
                .orElse(null);
    }

    /** Same actor-resolution pattern as {@code AuditService} -- the JWT subject of the current request, or null outside one. */
    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
