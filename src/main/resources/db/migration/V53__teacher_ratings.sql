-- Human Resource -> Teachers Rating: students rate their teachers (1 to 5 stars with a comment); a rating stays
-- Pending until the school approves it, and only approved ratings count toward a teacher's average.

CREATE TABLE teacher_ratings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_profile_id UUID NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL,
    comment VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT teacher_ratings_rating_check CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT teacher_ratings_status_check CHECK (status IN ('PENDING', 'APPROVED')),
    -- A student rates a teacher once.
    CONSTRAINT teacher_ratings_staff_student_key UNIQUE (staff_profile_id, student_id)
);

CREATE INDEX teacher_ratings_staff_idx ON teacher_ratings(staff_profile_id);
CREATE INDEX teacher_ratings_status_idx ON teacher_ratings(status);

INSERT INTO permissions (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('TEACHER_RATING_VIEW'), ('TEACHER_RATING_MANAGE')) AS new_permissions(name)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = new_permissions.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name IN ('SUPER_ADMIN', 'SCHOOL_ADMIN', 'PRINCIPAL') AND p.name IN ('TEACHER_RATING_VIEW', 'TEACHER_RATING_MANAGE')
ON CONFLICT DO NOTHING;
