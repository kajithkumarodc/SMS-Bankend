package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} powers the multi-field search/filter (applicant
 * name, phone, status, source, class, assigned staff, date range) -- same pattern
 * as {@code AuditLogRepository}: composing optional filters as JPQL {@code :x is
 * null} predicates trips up Postgres's type inference on some parameter types, so
 * a {@link org.springframework.data.jpa.domain.Specification} is used instead.
 */
public interface AdmissionEnquiryRepository
        extends JpaRepository<AdmissionEnquiry, UUID>, JpaSpecificationExecutor<AdmissionEnquiry> {

    /** The next value of {@code enquiry_number_seq} -- see the V23 migration comment for why a real sequence. */
    @Query(value = "SELECT nextval('enquiry_number_seq')", nativeQuery = true)
    long nextEnquiryNumberSeq();

    long countByArchivedFalse();

    long countByStatusAndArchivedFalse(String status);

    long countByConvertedStudentIdIsNotNull();

    /** "Follow-ups due": has a follow-up date on or before {@code onOrBefore} and isn't in a closed status. */
    long countByFollowUpDateLessThanEqualAndArchivedFalseAndStatusNotIn(LocalDate onOrBefore, Collection<String> closedStatuses);

    List<AdmissionEnquiry> findTop5ByArchivedFalseOrderByCreatedAtDesc();

    @Query("select e.sourceId as sourceId, count(e) as total from AdmissionEnquiry e "
            + "where e.archived = false group by e.sourceId")
    List<SourceCount> countBySource();

    @Query("select e.classId as classId, count(e) as total from AdmissionEnquiry e "
            + "where e.archived = false group by e.classId")
    List<ClassCount> countByClass();

    interface SourceCount {
        UUID getSourceId();

        long getTotal();
    }

    interface ClassCount {
        UUID getClassId();

        long getTotal();
    }
}
