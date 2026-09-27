package com.smsapp.face;

import com.smsapp.audit.AuditService;
import com.smsapp.common.SchoolClock;
import com.smsapp.face.CapturePhotoPurgeService.PurgeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapturePhotoPurgeServiceTest {

    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");

    @Mock
    private AttendanceCaptureRepository captureRepository;

    @Mock
    private AuditService auditService;

    @TempDir
    Path storage;

    private CapturePhotoPurgeService service() {
        return new CapturePhotoPurgeService(captureRepository, auditService, CLOCK,
                storage.toString(), 500);
    }

    private AttendanceCapture capture(String photoPath) {
        AttendanceCapture capture = new AttendanceCapture();
        capture.setId(UUID.randomUUID());
        capture.setSectionId(UUID.randomUUID());
        capture.setCaptureDate(CLOCK.today());
        capture.setPhotoPath(photoPath);
        capture.setPhotoPurgeAfter(CLOCK.now().minusDays(1));
        return capture;
    }

    private void due(AttendanceCapture... captures) {
        when(captureRepository
                .findByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqualOrderByPhotoPurgeAfterAsc(
                        any(OffsetDateTime.class), any(Limit.class)))
                .thenReturn(List.of(captures));
    }

    @BeforeEach
    void stubSave() {
        org.mockito.Mockito.lenient().when(captureRepository.save(any()))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void deletesAnOverduePhotoAndClearsThePathOnTheRow() throws IOException {
        Path photo = Files.writeString(storage.resolve("room.jpg"), "image-bytes");
        AttendanceCapture overdue = capture(photo.toString());
        due(overdue);

        PurgeResult result = service().purgeDuePhotos();

        assertThat(Files.exists(photo)).isFalse();
        assertThat(overdue.getPhotoPath()).isNull();
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(captureRepository).save(overdue);
    }

    /**
     * The row survives the photo. Everything except the image is the audit trail behind
     * an attendance mark, so purging must not take the capture with it.
     */
    @Test
    void keepsTheCaptureRowItselfAndItsDetectionMetadata() throws IOException {
        Path photo = Files.writeString(storage.resolve("room.jpg"), "image-bytes");
        AttendanceCapture overdue = capture(photo.toString());
        overdue.setFacesDetected(23);
        overdue.setModelVersion("insightface/buffalo_l/arcface-512/v1");
        due(overdue);

        service().purgeDuePhotos();

        assertThat(overdue.getFacesDetected()).isEqualTo(23);
        assertThat(overdue.getModelVersion()).isEqualTo("insightface/buffalo_l/arcface-512/v1");
        assertThat(overdue.getSectionId()).isNotNull();
        verify(captureRepository, never()).delete(any());
    }

    /**
     * Self-healing: a previous sweep that deleted the file but failed before saving the
     * row leaves a record pointing at a missing file. The goal is the photo's absence,
     * not the act of deleting, so that counts as done and the column gets cleared.
     */
    @Test
    void treatsAnAlreadyMissingFileAsPurged() {
        AttendanceCapture overdue = capture(storage.resolve("never-existed.jpg").toString());
        due(overdue);

        PurgeResult result = service().purgeDuePhotos();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(overdue.getPhotoPath()).isNull();
    }

    /**
     * A stored path outside the storage root means corrupted or tampered data. Deleting
     * whatever it points at would turn a bad row into arbitrary file deletion, so the
     * sweeper refuses and leaves it for a human.
     */
    @Test
    void refusesToDeleteOutsideTheStorageRootAndLeavesTheRowAlone() throws IOException {
        Path outside = Files.createTempFile("not-ours", ".jpg");
        try {
            AttendanceCapture rogue = capture(outside.toString());
            due(rogue);

            PurgeResult result = service().purgeDuePhotos();

            assertThat(Files.exists(outside)).isTrue();
            assertThat(rogue.getPhotoPath()).isEqualTo(outside.toString());
            assertThat(result.deleted()).isZero();
            assertThat(result.failed()).isEqualTo(1);
            verify(captureRepository, never()).save(any());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** A traversal path that resolves back outside the root must be refused too. */
    @Test
    void refusesATraversalPath() throws IOException {
        Path escape = storage.resolve("..").resolve("escaped.jpg");
        Files.writeString(escape, "not-ours");
        try {
            AttendanceCapture rogue = capture(escape.toString());
            due(rogue);

            assertThat(service().purgeDuePhotos().failed()).isEqualTo(1);
            assertThat(Files.exists(escape)).isTrue();
        } finally {
            Files.deleteIfExists(escape);
        }
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        due();

        PurgeResult result = service().purgeDuePhotos();

        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isZero();
        verify(captureRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any());
    }

    @Test
    void auditsEachPurgeSoTheDeletionIsProvable() throws IOException {
        Path photo = Files.writeString(storage.resolve("a.jpg"), "x");
        due(capture(photo.toString()));

        service().purgeDuePhotos();

        verify(auditService).log(any(), any(), any(), any());
    }

    /** A failure on one photo must not stop the rest of the batch. */
    @Test
    void continuesPastAFailedPhoto() throws IOException {
        Path outside = Files.createTempFile("not-ours", ".jpg");
        Path good = Files.writeString(storage.resolve("good.jpg"), "x");
        try {
            due(capture(outside.toString()), capture(good.toString()));

            PurgeResult result = service().purgeDuePhotos();

            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.deleted()).isEqualTo(1);
            assertThat(Files.exists(good)).isFalse();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** The scheduled entry point must swallow failures, or the executor unschedules it. */
    @Test
    void theScheduledSweepSwallowsFailuresSoItRunsAgainTomorrow() {
        when(captureRepository
                .findByPhotoPathIsNotNullAndPhotoPurgeAfterLessThanEqualOrderByPhotoPurgeAfterAsc(
                        any(OffsetDateTime.class), any(Limit.class)))
                .thenThrow(new RuntimeException("database is down"));

        service().sweep();
    }
}
