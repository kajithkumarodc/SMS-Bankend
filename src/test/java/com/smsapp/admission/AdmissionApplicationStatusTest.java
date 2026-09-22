package com.smsapp.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The status state machine (plan Phase 4.5 part 9) -- every allowed and forbidden transition. */
class AdmissionApplicationStatusTest {

    @Test
    void submittedCanOnlyMoveToUnderReview() {
        assertThat(AdmissionApplicationStatus.canTransition("SUBMITTED", "UNDER_REVIEW")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("SUBMITTED", "APPROVED")).isFalse();
        assertThat(AdmissionApplicationStatus.canTransition("SUBMITTED", "REJECTED")).isFalse();
        assertThat(AdmissionApplicationStatus.canTransition("SUBMITTED", "WAITLISTED")).isFalse();
    }

    @Test
    void underReviewCanMoveToApprovedRejectedOrWaitlisted() {
        assertThat(AdmissionApplicationStatus.canTransition("UNDER_REVIEW", "APPROVED")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("UNDER_REVIEW", "REJECTED")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("UNDER_REVIEW", "WAITLISTED")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("UNDER_REVIEW", "SUBMITTED")).isFalse();
    }

    @Test
    void approvedIsTerminal() {
        assertThat(AdmissionApplicationStatus.canTransition("APPROVED", "UNDER_REVIEW")).isFalse();
        assertThat(AdmissionApplicationStatus.canTransition("APPROVED", "REJECTED")).isFalse();
        assertThat(AdmissionApplicationStatus.canTransition("APPROVED", "APPROVED")).isFalse();
    }

    @Test
    void rejectedAndWaitlistedCanOnlyReopenIntoUnderReview_neverDirectlyToApproved() {
        assertThat(AdmissionApplicationStatus.canTransition("REJECTED", "UNDER_REVIEW")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("REJECTED", "APPROVED")).isFalse();
        assertThat(AdmissionApplicationStatus.canTransition("WAITLISTED", "UNDER_REVIEW")).isTrue();
        assertThat(AdmissionApplicationStatus.canTransition("WAITLISTED", "APPROVED")).isFalse();
    }
}
