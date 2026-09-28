-- Face-assisted attendance, phase 2: detection only, no recognition.
--
-- A teacher photographs the class, the detector finds faces, and the teacher tags
-- each one by hand. That hand-tagging is the point: enrolment photos taken at a desk
-- do not match faces under classroom lighting, so this is what builds the corpus
-- phase 3 actually needs.
--
-- The privacy shape matters more than the schema here. A classroom photo contains
-- children whose guardians may not have consented, and detection produces a vector
-- for every one of them. So:
--   * every detected face is written here, embedding included, because the teacher
--     needs to be able to tag any of them and tagging happens after detection;
--   * a row may only be tied to a student who has live consent (enforced in
--     AttendanceCaptureService);
--   * anything still untagged when the capture's retention window closes is deleted
--     along with the photo. Untagged means "we never established a basis to keep
--     this", so it goes.
-- The effect is that an un-consented child's vector exists for at most the retention
-- window and is never associated with their identity.

CREATE TABLE attendance_capture_faces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capture_id UUID NOT NULL REFERENCES attendance_captures(id) ON DELETE CASCADE,
    -- Position in the detector's output, which is ordered largest face first. Stable
    -- for the life of the capture, so the app can address a face without holding ids.
    face_index INT NOT NULL,
    -- "x,y,w,h" in pixels of the photo as submitted, for cropping the face back out.
    bbox VARCHAR(60) NOT NULL,
    -- 512 float32, little-endian, same layout as student_face_enrolments.
    embedding BYTEA NOT NULL,
    dimensions INT NOT NULL,
    quality_score REAL,
    -- Null until a teacher tags it. Only ever set to a student with live consent.
    assigned_student_id UUID REFERENCES students(id) ON DELETE SET NULL,
    assigned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attendance_capture_faces_capture_index_key UNIQUE (capture_id, face_index)
);

CREATE INDEX attendance_capture_faces_capture_id_idx ON attendance_capture_faces(capture_id);
-- The corpus query: every hand-tagged face for a student, which is what phase 3/4
-- train and tune against.
CREATE INDEX attendance_capture_faces_assigned_student_id_idx
    ON attendance_capture_faces(assigned_student_id) WHERE assigned_student_id IS NOT NULL;
-- Drives the purge of untagged faces.
CREATE INDEX attendance_capture_faces_untagged_idx
    ON attendance_capture_faces(capture_id) WHERE assigned_student_id IS NULL;
