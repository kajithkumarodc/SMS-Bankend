package com.smsapp.face;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceConsentRepository extends JpaRepository<FaceConsent, UUID> {

    Optional<FaceConsent> findByStudentIdAndScope(UUID studentId, String scope);

    /**
     * Live consents among a set of students -- how the enrolment and matching paths
     * ask "which of this section may I process?" in one query rather than per student.
     */
    List<FaceConsent> findByStudentIdInAndScopeAndRevokedAtIsNull(
            Collection<UUID> studentIds, String scope);
}
