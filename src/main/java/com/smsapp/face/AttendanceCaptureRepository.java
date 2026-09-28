package com.smsapp.face;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AttendanceCaptureRepository extends JpaRepository<AttendanceCapture, UUID> {

    /**
     * Captures whose photo is due for deletion, oldest first, capped so one sweep
     * cannot turn into an unbounded transaction after an outage. Matches the partial
     * index in V30 (photo_purge_after, where photo_path is not null).
     */
    List<AttendanceCapture> findByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqualOrderByPhotoPurgeAfterAsc(
            OffsetDateTime cutoff, Limit limit);

    /** A section's captures for one day, newest first. */
    List<AttendanceCapture> findBySectionIdAndCaptureDateOrderByCreatedAtDesc(
            UUID sectionId, java.time.LocalDate captureDate);

    /** How much is still awaiting deletion -- surfaced for monitoring. */
    long countByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqual(OffsetDateTime cutoff);
}
