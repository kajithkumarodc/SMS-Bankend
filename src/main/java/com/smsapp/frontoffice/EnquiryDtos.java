package com.smsapp.frontoffice;

import com.smsapp.student.StudentDtos.StudentResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Front Office / Admission Enquiry API. Entities are never exposed directly. */
final class EnquiryDtos {

    private EnquiryDtos() {
    }

    /** Digits with an optional leading +, and spaces, dashes or brackets between them -- 6 to 30 characters. */
    static final String PHONE_PATTERN = "^\\+?[0-9][0-9 ()-]{4,28}[0-9]$";

    /**
     * Body for both {@code POST /api/v1/enquiries} and {@code PUT /api/v1/enquiries/{id}} -- the
     * Admission Enquiry form. Required fields match the form: name, phone, date, next follow-up
     * date and source.
     */
    record EnquiryRequest(
            @NotBlank @Size(max = 200) String applicantName,
            @NotBlank @Size(max = 30)
            @Pattern(regexp = PHONE_PATTERN, message = "must be a valid phone number") String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 1000) String address,
            @Size(max = 2000) String description,
            /** Shown as "Note" on the form. */
            @Size(max = 2000) String remarks,
            @NotNull LocalDate enquiryDate,
            /** The next planned follow-up; must not be before {@code enquiryDate}. */
            @NotNull LocalDate nextFollowUpDate,
            UUID assignedStaffUserId,
            UUID referenceId,
            @NotNull UUID sourceId,
            UUID classId,
            @Min(0) @Max(99) Integer numberOfChildren,
            /** Not on the form. Kept for API clients that record it; editing without it leaves it unchanged. */
            @Size(max = 200) String guardianName,
            /** Not on the form. Create defaults it to the current academic year; editing without it leaves it unchanged. */
            UUID academicYearId) {
    }

    /** Body for {@code PATCH /api/v1/enquiries/{id}/status}. */
    record ChangeEnquiryStatusRequest(@NotBlank String status) {
    }

    /** Body for {@code PATCH /api/v1/enquiries/{id}/archive}. */
    record ArchiveEnquiryRequest(boolean archived) {
    }

    /** Body for {@code POST /api/v1/enquiries/{id}/link-application} (plan Phase 4.5 part 17). */
    record LinkApplicationRequest(@NotNull UUID applicationId) {
    }

    /** Body for {@code POST /api/v1/enquiries/{id}/follow-ups}. The acting staff member is the caller (JWT subject). */
    record RecordFollowUpRequest(
            @NotNull LocalDate followUpDate,
            @NotBlank String followUpType,
            String notes,
            LocalDate nextFollowUpDate) {
    }

    record FollowUpResponse(
            UUID id,
            UUID enquiryId,
            LocalDate followUpDate,
            String followUpType,
            String notes,
            UUID staffUserId,
            String staffName,
            LocalDate nextFollowUpDate,
            OffsetDateTime createdAt) {

        static FollowUpResponse from(EnquiryFollowUp followUp, String staffName) {
            return new FollowUpResponse(followUp.getId(), followUp.getEnquiryId(), followUp.getFollowUpDate(),
                    followUp.getFollowUpType(), followUp.getNotes(), followUp.getStaffUserId(), staffName,
                    followUp.getNextFollowUpDate(), followUp.getCreatedAt());
        }
    }

    record EnquiryResponse(
            UUID id,
            String enquiryNumber,
            String applicantName,
            String guardianName,
            String phone,
            String email,
            String address,
            String description,
            UUID classId,
            String className,
            LocalDate enquiryDate,
            UUID sourceId,
            String sourceName,
            UUID referenceId,
            String referenceName,
            Integer numberOfChildren,
            UUID academicYearId,
            String academicYearName,
            UUID assignedStaffUserId,
            String assignedStaffName,
            /** The next planned follow-up date. */
            LocalDate followUpDate,
            String followUpNotes,
            LocalDate lastFollowUpDate,
            String status,
            /** Shown as "Note" on the form. */
            String remarks,
            UUID convertedStudentId,
            boolean archived,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static EnquiryResponse from(AdmissionEnquiry e, EnquiryNames names) {
            return new EnquiryResponse(e.getId(), e.getEnquiryNumber(), e.getApplicantName(), e.getGuardianName(),
                    e.getPhone(), e.getEmail(), e.getAddress(), e.getDescription(), e.getClassId(),
                    names.className(), e.getEnquiryDate(), e.getSourceId(), names.sourceName(), e.getReferenceId(),
                    names.referenceName(),
                    e.getNumberOfChildren() == null ? null : e.getNumberOfChildren().intValue(),
                    e.getAcademicYearId(), names.academicYearName(), e.getAssignedStaffUserId(),
                    names.assignedStaffName(), e.getFollowUpDate(), e.getFollowUpNotes(), e.getLastFollowUpDate(),
                    e.getStatus(), e.getRemarks(), e.getConvertedStudentId(), e.isArchived(), e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }

    /** Display names for an enquiry's ids, resolved by {@code EnquiryService} (null when unset or unknown). */
    record EnquiryNames(String sourceName, String referenceName, String className, String academicYearName,
                        String assignedStaffName) {
    }

    record CreateEnquirySourceRequest(@NotBlank @Size(max = 100) String name) {
    }

    record EnquirySourceResponse(UUID id, String name, boolean active) {

        static EnquirySourceResponse from(EnquirySource source) {
            return new EnquirySourceResponse(source.getId(), source.getName(), source.isActive());
        }
    }

    record CreateEnquiryReferenceRequest(@NotBlank @Size(max = 100) String name) {
    }

    record EnquiryReferenceResponse(UUID id, String name, boolean active) {

        static EnquiryReferenceResponse from(EnquiryReference reference) {
            return new EnquiryReferenceResponse(reference.getId(), reference.getName(), reference.isActive());
        }
    }

    record AssignableStaffResponse(UUID id, String fullName, String email) {
    }

    /**
     * One row of the "Enquiries by source/class" breakdown. {@code id} is null for the
     * "Not specified" bucket (no source/class was recorded) -- the frontend uses that to
     * tell an unfiltered/unclickable row apart from a real source or class.
     */
    record EnquiryGroupCount(UUID id, String label, long count) {
    }

    /** Which academic year the dashboard counts below are scoped to, if any is marked current. */
    record AcademicYearBadge(UUID id, String name) {
    }

    /** Real database counts for the Front Office dashboard -- no hardcoded numbers (plan "Dashboard rule"). */
    record EnquirySummaryResponse(
            long totalEnquiries,
            long activeEnquiries,
            long followUpsDue,
            long converted,
            long lost,
            List<EnquiryGroupCount> bySource,
            List<EnquiryGroupCount> byClass,
            AcademicYearBadge academicYear,
            List<EnquiryResponse> recent) {
    }

    /** Result of converting an enquiry -- both the newly created student and the now-WON enquiry. */
    record EnquiryConversionResult(StudentResponse student, EnquiryResponse enquiry) {
    }
}
