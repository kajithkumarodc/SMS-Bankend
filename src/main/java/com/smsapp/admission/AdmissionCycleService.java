package com.smsapp.admission;

import com.smsapp.academics.AcademicYearRepository;
import com.smsapp.admission.AdmissionDtos.AdmissionCycleResponse;
import com.smsapp.admission.AdmissionDtos.CreateAdmissionCycleRequest;
import com.smsapp.admission.AdmissionDtos.PublicOpenCycleResponse;
import com.smsapp.admission.AdmissionDtos.UpdateAdmissionCycleRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admission cycles (plan Phase 4.5 part 1/9): the window a public applicant is allowed to submit
 * against. At most one may be OPEN per school at a time, enforced both here (a clear 409 before
 * hitting the database) and by V27's partial unique index (the actual guarantee under concurrency).
 */
@Service
public class AdmissionCycleService {

    private final AdmissionCycleRepository cycleRepository;
    private final SchoolRepository schoolRepository;
    private final AcademicYearRepository academicYearRepository;
    private final AuditService auditService;

    public AdmissionCycleService(AdmissionCycleRepository cycleRepository, SchoolRepository schoolRepository,
                                  AcademicYearRepository academicYearRepository, AuditService auditService) {
        this.cycleRepository = cycleRepository;
        this.schoolRepository = schoolRepository;
        this.academicYearRepository = academicYearRepository;
        this.auditService = auditService;
    }

    /** @throws ApiException 404 if the school/academic year doesn't exist, 400 if the date range is invalid. */
    @Transactional
    public AdmissionCycle create(CreateAdmissionCycleRequest request, UUID actorUserId) {
        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (!academicYearRepository.existsById(request.academicYearId())) {
            throw new ApiException("Academic year not found", HttpStatus.NOT_FOUND);
        }
        requireValidDateRange(request.openDate(), request.closeDate());

        AdmissionCycle cycle = new AdmissionCycle();
        cycle.setSchoolId(request.schoolId());
        cycle.setAcademicYearId(request.academicYearId());
        cycle.setName(request.name().trim());
        cycle.setDescription(blankToNull(request.description()));
        cycle.setOpenDate(request.openDate());
        cycle.setCloseDate(request.closeDate());
        cycle.setStatus(AdmissionCycleStatus.DRAFT);
        cycle.setCreatedByUserId(actorUserId);

        AdmissionCycle saved = cycleRepository.save(cycle);
        auditService.log(AuditActions.ADMISSION_CYCLE_CREATED, AuditActions.ADMISSION_CYCLE, saved.getId(),
                Map.of("name", saved.getName(), "schoolId", saved.getSchoolId().toString()));
        return saved;
    }

    /** @throws ApiException 404 if no such cycle, 400 if the date range is invalid. */
    @Transactional
    public AdmissionCycle update(UUID id, UpdateAdmissionCycleRequest request) {
        AdmissionCycle cycle = requireCycle(id);
        requireValidDateRange(request.openDate(), request.closeDate());

        cycle.setName(request.name().trim());
        cycle.setDescription(blankToNull(request.description()));
        cycle.setOpenDate(request.openDate());
        cycle.setCloseDate(request.closeDate());
        AdmissionCycle saved = cycleRepository.save(cycle);

        auditService.log(AuditActions.ADMISSION_CYCLE_UPDATED, AuditActions.ADMISSION_CYCLE, id,
                Map.of("name", saved.getName()));
        return saved;
    }

    /**
     * @throws ApiException 404 if no such cycle, 409 if it is already open, or another cycle for the same
     *         school is already open.
     */
    @Transactional
    public AdmissionCycle open(UUID id) {
        AdmissionCycle cycle = requireCycle(id);
        if (AdmissionCycleStatus.OPEN.equals(cycle.getStatus())) {
            throw new ApiException("This admission cycle is already open", HttpStatus.CONFLICT);
        }
        if (cycleRepository.existsBySchoolIdAndStatus(cycle.getSchoolId(), AdmissionCycleStatus.OPEN)) {
            throw new ApiException(
                    "Another admission cycle is already open for this school -- close it first",
                    HttpStatus.CONFLICT);
        }

        String previousStatus = cycle.getStatus();
        cycle.setStatus(AdmissionCycleStatus.OPEN);
        AdmissionCycle saved;
        try {
            saved = cycleRepository.saveAndFlush(cycle);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent open() for the same school.
            throw new ApiException(
                    "Another admission cycle is already open for this school -- close it first",
                    HttpStatus.CONFLICT);
        }

        auditService.log(AuditActions.ADMISSION_CYCLE_OPENED, AuditActions.ADMISSION_CYCLE, id,
                Map.of("from", previousStatus, "to", AdmissionCycleStatus.OPEN));
        return saved;
    }

    /** @throws ApiException 404 if no such cycle, 409 if it isn't currently open. */
    @Transactional
    public AdmissionCycle close(UUID id) {
        AdmissionCycle cycle = requireCycle(id);
        if (!AdmissionCycleStatus.OPEN.equals(cycle.getStatus())) {
            throw new ApiException("This admission cycle is not open", HttpStatus.CONFLICT);
        }
        cycle.setStatus(AdmissionCycleStatus.CLOSED);
        AdmissionCycle saved = cycleRepository.save(cycle);

        auditService.log(AuditActions.ADMISSION_CYCLE_CLOSED, AuditActions.ADMISSION_CYCLE, id,
                Map.of("from", AdmissionCycleStatus.OPEN, "to", AdmissionCycleStatus.CLOSED));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AdmissionCycle> list() {
        return cycleRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public AdmissionCycle get(UUID id) {
        return requireCycle(id);
    }

    @Transactional(readOnly = true)
    public AdmissionCycleResponse toResponse(AdmissionCycle cycle) {
        String yearName = academicYearRepository.findById(cycle.getAcademicYearId())
                .map(com.smsapp.academics.AcademicYear::getName).orElse(null);
        return AdmissionCycleResponse.from(cycle, yearName);
    }

    /**
     * The public application page's only view of a cycle (plan part 2): whichever cycle is OPEN for
     * this school right now.
     *
     * @throws ApiException 404 ("Online admissions are currently closed") if none is open, or if the
     *         school doesn't exist -- reported identically so the endpoint never confirms a school id.
     */
    @Transactional(readOnly = true)
    public PublicOpenCycleResponse currentOpenCycle(UUID schoolId) {
        AdmissionCycle cycle = cycleRepository
                .findFirstBySchoolIdAndStatusOrderByOpenDateDesc(schoolId, AdmissionCycleStatus.OPEN)
                .orElseThrow(() -> new ApiException("Online admissions are currently closed", HttpStatus.NOT_FOUND));
        String yearName = academicYearRepository.findById(cycle.getAcademicYearId())
                .map(com.smsapp.academics.AcademicYear::getName).orElse(null);
        return new PublicOpenCycleResponse(cycle.getId(), cycle.getName(), cycle.getDescription(), yearName,
                cycle.getOpenDate(), cycle.getCloseDate());
    }

    private AdmissionCycle requireCycle(UUID id) {
        return cycleRepository.findById(id)
                .orElseThrow(() -> new ApiException("Admission cycle not found", HttpStatus.NOT_FOUND));
    }

    private static void requireValidDateRange(java.time.LocalDate openDate, java.time.LocalDate closeDate) {
        if (!closeDate.isAfter(openDate)) {
            throw new ApiException("Close date must be after open date", HttpStatus.BAD_REQUEST);
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
