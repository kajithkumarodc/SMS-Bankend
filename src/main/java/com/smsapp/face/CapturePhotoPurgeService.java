package com.smsapp.face;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.SchoolClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Deletes classroom photos once their retention window has passed.
 *
 * <p>A capture's photo is kept only long enough to resolve a dispute about an
 * attendance mark; the embeddings and the match record survive, the image does not.
 * Without this job the {@code photo_purge_after} column would be a promise the system
 * never keeps, and "we delete class photos within 7 days" is a claim made to parents
 * on the consent screen -- so it has to be true.
 *
 * <p>Disabled with {@code app.face.purge.enabled=false} (the test profile does this, so
 * a sweep never races an integration test's fixtures).
 */
@Service
@ConditionalOnProperty(value = "app.face.purge.enabled", havingValue = "true", matchIfMissing = true)
public class CapturePhotoPurgeService {

    private static final Logger log = LoggerFactory.getLogger(CapturePhotoPurgeService.class);

    private final AttendanceCaptureRepository captureRepository;
    private final AttendanceCaptureFaceRepository faceRepository;
    private final AuditService auditService;
    private final SchoolClock clock;
    private final Path baseDir;
    private final int batchSize;

    public CapturePhotoPurgeService(AttendanceCaptureRepository captureRepository,
                                    AttendanceCaptureFaceRepository faceRepository,
                                    AuditService auditService,
                                    SchoolClock clock,
                                    @Value("${app.storage.base-dir}") String baseDir,
                                    @Value("${app.face.purge.batch-size:500}") int batchSize) {
        this.captureRepository = captureRepository;
        this.faceRepository = faceRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.batchSize = batchSize;
    }

    /**
     * Runs on a fixed delay rather than a cron: the work is "whatever is overdue right
     * now", so it does not matter what time of day it happens, and a fixed delay cannot
     * pile up if one sweep runs long.
     *
     * <p>Single-instance assumption: two replicas would both sweep, which is harmless
     * (deleting an already-deleted file is a no-op and the row update is idempotent)
     * but wasteful. If this ever scales out, take a lock here.
     */
    @Scheduled(
            fixedDelayString = "${app.face.purge.interval-ms:3600000}",
            initialDelayString = "${app.face.purge.initial-delay-ms:120000}")
    public void sweep() {
        try {
            PurgeResult result = purgeDuePhotos();
            if (result.deleted() > 0 || result.failed() > 0) {
                log.info("capture photo purge: {} deleted, {} failed, {} still due",
                        result.deleted(), result.failed(), result.remaining());
            }
        } catch (RuntimeException error) {
            // A scheduled method that throws is silently unscheduled by some executors;
            // swallowing here keeps the sweep running tomorrow after a transient failure.
            log.error("capture photo purge failed", error);
        }
    }

    public record PurgeResult(int deleted, int failed, long remaining) {
    }

    /**
     * Deletes every photo whose retention window has closed, up to the batch cap.
     *
     * <p>The file is deleted <em>before</em> the column is cleared, deliberately. That
     * order is self-healing: if the delete succeeds and the row update then fails, the
     * next sweep finds the row again, finds the file already gone, and clears the column.
     * The reverse order would orphan the file on disk with nothing left pointing at it.
     */
    @Transactional
    public PurgeResult purgeDuePhotos() {
        OffsetDateTime now = clock.now();
        List<AttendanceCapture> due = captureRepository
                .findByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqualOrderByPhotoPurgeAfterAsc(
                        now, Limit.of(batchSize));

        int deleted = 0;
        int failed = 0;
        int facesDropped = 0;
        for (AttendanceCapture capture : due) {
            if (deletePhoto(capture)) {
                // Untagged faces go with the photo. An untagged face is a vector for a
                // child nobody identified, so no consent basis was ever established for
                // it -- keeping it past the retention window would be exactly the thing
                // the consent design exists to prevent. Tagged faces survive: those are
                // the consented, hand-labelled corpus phase 3 trains on.
                facesDropped += dropUntaggedFaces(capture.getId());

                capture.setPhotoPath(null);
                captureRepository.save(capture);
                deleted++;
                auditService.log(AuditActions.CAPTURE_PHOTO_PURGED, AuditActions.ATTENDANCE_CAPTURE,
                        capture.getId(), Map.of("sectionId", String.valueOf(capture.getSectionId()),
                                "captureDate", String.valueOf(capture.getCaptureDate())));
            } else {
                failed++;
            }
        }
        if (facesDropped > 0) {
            log.info("capture photo purge: dropped {} untagged face vectors", facesDropped);
        }

        long remaining = captureRepository.countByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqual(now);
        return new PurgeResult(deleted, failed, remaining);
    }

    private int dropUntaggedFaces(java.util.UUID captureId) {
        List<AttendanceCaptureFace> untagged =
                faceRepository.findByCaptureIdAndAssignedStudentIdIsNull(captureId);
        if (untagged.isEmpty()) {
            return 0;
        }
        faceRepository.deleteAll(untagged);
        auditService.log(AuditActions.CAPTURE_FACES_PURGED, AuditActions.ATTENDANCE_CAPTURE,
                captureId, Map.of("untaggedFacesDeleted", String.valueOf(untagged.size())));
        return untagged.size();
    }

    /**
     * @return true when the file is gone -- including when it was already missing, since
     *         the goal is absence, not the act of deleting.
     */
    private boolean deletePhoto(AttendanceCapture capture) {
        Path file = Path.of(capture.getPhotoPath()).toAbsolutePath().normalize();

        // Never delete outside the configured storage root. A stored path should always
        // be inside it, so one that is not means corrupted or tampered data -- refuse
        // rather than follow it, and leave it for a human to look at.
        if (!file.startsWith(baseDir)) {
            log.error("refusing to purge capture {}: photo path {} is outside the storage root",
                    capture.getId(), file);
            return false;
        }

        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException unreadable) {
            log.warn("could not delete capture photo {} for capture {}", file, capture.getId(), unreadable);
            return false;
        }
    }
}
