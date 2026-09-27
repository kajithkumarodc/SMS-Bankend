-- Face-assisted attendance, phase 1: consent and enrolment. No recognition runs
-- against these tables yet -- this migration only gives the data somewhere to live.
--
-- Deliberately NO pgvector. The plan originally reached for it, but matching is
-- always scoped to one section's roster (about 40 embeddings), so a brute-force
-- cosine scan in application code is microseconds and needs no index. Even a
-- whole-school scan is a few thousand vectors. Requiring the `vector` extension
-- would add a hard dependency on a non-stock Postgres image -- the extension is
-- absent from the plain postgres:17 image this project uses locally -- for a
-- search problem far too small to need it. Embeddings are stored as `bytea`
-- (512 float32 = 2 KB) via com.smsapp.face.Embeddings.

-- Guardian consent, one row per student. Re-granting updates the row in place;
-- the history lives in audit_log like every other sensitive change here.
-- Consent is collected regardless of what the DPDP education exemption permits:
-- it is the defensible posture, and it is what makes opt-out meaningful.
CREATE TABLE face_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    -- Who granted it. Null only if an admin recorded a paper consent form.
    guardian_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    -- Narrow by design: the only purpose this consent covers. A second purpose
    -- would be a second scope value, never a widening of this one.
    scope VARCHAR(40) NOT NULL DEFAULT 'FACE_ATTENDANCE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX face_consents_student_scope_key ON face_consents(student_id, scope);

-- Reference embeddings. Several rows per student (different angles, and refreshed
-- each term as children's faces change), so no unique constraint on student_id.
CREATE TABLE student_face_enrolments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    -- 512 float32, little-endian. `dimensions` is stored rather than assumed so a
    -- future model with a different output size is a data question, not a crash.
    embedding BYTEA NOT NULL,
    dimensions INT NOT NULL,
    -- Detector's confidence that this crop is a usable face, 0..1. Low-quality
    -- enrolments are the main cause of bad matches, so it is recorded and filtered on.
    quality_score REAL,
    source_photo_ref VARCHAR(500),
    -- Embeddings from different models are not comparable. Changing the model
    -- invalidates every row; this column is how that is detected rather than
    -- silently producing nonsense distances.
    model_version VARCHAR(80) NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enrolled_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX student_face_enrolments_student_id_idx ON student_face_enrolments(student_id);
CREATE INDEX student_face_enrolments_model_version_idx ON student_face_enrolments(model_version);

-- One classroom photo submitted for processing.
CREATE TABLE attendance_captures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES sections(id),
    capture_date DATE NOT NULL,
    captured_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    model_version VARCHAR(80),
    faces_detected INT,
    -- Nullable, and purged: the photo is kept only long enough to resolve a
    -- dispute, then deleted by a scheduled sweep. Retaining forty children's
    -- faces indefinitely fails the "minimum data necessary" condition the DPDP
    -- education exemption is conditioned on.
    photo_path VARCHAR(500),
    photo_purge_after TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX attendance_captures_section_id_capture_date_idx
    ON attendance_captures(section_id, capture_date DESC);
-- Drives the purge sweep: find captures whose photo is due for deletion.
CREATE INDEX attendance_captures_photo_purge_after_idx
    ON attendance_captures(photo_purge_after) WHERE photo_path IS NOT NULL;

-- What the model proposed, and what the teacher did about it. `teacher_accepted`
-- is the labelled dataset phase 4 tunes thresholds against.
CREATE TABLE attendance_capture_matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capture_id UUID NOT NULL REFERENCES attendance_captures(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    confidence REAL NOT NULL,
    -- "x,y,w,h" in pixels of the submitted image, for drawing the crop back.
    bbox VARCHAR(60),
    -- Only ever PRESENT: the system never proposes an absence, because a face it
    -- did not find is not evidence that the student was away.
    proposed_status VARCHAR(20) NOT NULL,
    teacher_accepted BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attendance_capture_matches_proposed_status_check
        CHECK (proposed_status = 'PRESENT')
);

CREATE UNIQUE INDEX attendance_capture_matches_capture_student_key
    ON attendance_capture_matches(capture_id, student_id);
CREATE INDEX attendance_capture_matches_student_id_idx ON attendance_capture_matches(student_id);

-- Provenance on the attendance record itself. Without these a disputed mark
-- cannot be audited, and there is no way to measure whether the model helps.
-- Existing rows predate the feature and are all genuinely MANUAL.
ALTER TABLE attendance_records
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN confidence REAL;

ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_source_check
    CHECK (source IN ('MANUAL', 'FACE_ASSISTED'));

-- RBAC. Enrolment is an administrative act (it creates biometric records), so it
-- is not granted to TEACHER alongside ordinary attendance marking.
INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), 'FACE_ENROLMENT_MANAGE'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = 'FACE_ENROLMENT_MANAGE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN') AND p.name = 'FACE_ENROLMENT_MANAGE'
ON CONFLICT DO NOTHING;
