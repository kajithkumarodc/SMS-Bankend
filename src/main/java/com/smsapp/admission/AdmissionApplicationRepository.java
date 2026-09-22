package com.smsapp.admission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} powers the admin search/filter (status/cycle/class/date range/
 * text) -- same "avoid the Postgres `:x is null` parameter-type-inference trap" reasoning as
 * {@code StudentRepository}/{@code AdmissionEnquiryRepository}.
 */
public interface AdmissionApplicationRepository
        extends JpaRepository<AdmissionApplication, UUID>, JpaSpecificationExecutor<AdmissionApplication> {

    Optional<AdmissionApplication> findByApplicationNumber(String applicationNumber);

    /**
     * Public status lookup (plan part 15): the reference is matched together with the applicant's own
     * guardian email, case-insensitively -- an unmatched pair is reported identically to a
     * nonexistent reference by the caller, so the endpoint never confirms a reference exists on its
     * own.
     */
    Optional<AdmissionApplication> findByApplicationNumberAndGuardianEmailIgnoreCase(
            String applicationNumber, String guardianEmail);

    /** A real DB sequence (not {@code count(*) + 1}) so concurrent submissions never collide (V27). */
    @Query(value = "select nextval('admission_application_number_seq')", nativeQuery = true)
    long nextApplicationNumberSeq();

    /** Auto-generated student admission number at approval time -- its own sequence, never the application's own number (V27). */
    @Query(value = "select nextval('student_admission_number_seq')", nativeQuery = true)
    long nextStudentAdmissionNumberSeq();

    long countByStatus(String status);

    long countByAdmissionCycleId(UUID admissionCycleId);
}
