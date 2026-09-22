package com.smsapp.frontoffice;

import com.smsapp.student.StudentDtos.StudentResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response payloads for the Front Office / Admission Enquiry API. Entities are never exposed directly. */
final class EnquiryDtos {

    private EnquiryDtos() {
    }

    record CreateEnquiryRequest(
            @NotBlank @Size(max = 200) String applicantName,
            @Size(max = 200) String guardianName,
            @Size(max = 30) String phone,
            @Email @Size(max = 200) String email,
            UUID classId,
            LocalDate enquiryDate,
            UUID sourceId,
            UUID assignedStaffUserId,
            String remarks) {
    }

    record UpdateEnquiryRequest(
            @NotBlank @Size(max = 200) String applicantName,
            @Size(max = 200) String guardianName,
            @Size(max = 30) String phone,
            @Email @Size(max = 200) String email,
            UUID classId,
            UUID sourceId,
            UUID assignedStaffUserId,
            String remarks) {
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
            UUID classId,
            LocalDate enquiryDate,
            UUID sourceId,
            String sourceName,
            UUID assignedStaffUserId,
            String assignedStaffName,
            LocalDate followUpDate,
            String followUpNotes,
            String status,
            String remarks,
            UUID convertedStudentId,
            boolean archived,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static EnquiryResponse from(AdmissionEnquiry e, String sourceName, String assignedStaffName) {
            return new EnquiryResponse(e.getId(), e.getEnquiryNumber(), e.getApplicantName(), e.getGuardianName(),
                    e.getPhone(), e.getEmail(), e.getClassId(), e.getEnquiryDate(), e.getSourceId(), sourceName,
                    e.getAssignedStaffUserId(), assignedStaffName, e.getFollowUpDate(), e.getFollowUpNotes(),
                    e.getStatus(), e.getRemarks(), e.getConvertedStudentId(), e.isArchived(), e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }

    record CreateEnquirySourceRequest(@NotBlank @Size(max = 100) String name) {
    }

    record EnquirySourceResponse(UUID id, String name, boolean active) {

        static EnquirySourceResponse from(EnquirySource source) {
            return new EnquirySourceResponse(source.getId(), source.getName(), source.isActive());
        }
    }

    record AssignableStaffResponse(UUID id, String fullName, String email) {
    }

    /** Real database counts for the Front Office dashboard -- no hardcoded numbers (plan "Dashboard rule"). */
    record EnquirySummaryResponse(
            long totalEnquiries,
            long activeEnquiries,
            long followUpsDue,
            long converted,
            long lost,
            Map<String, Long> bySource,
            Map<String, Long> byClass,
            List<EnquiryResponse> recent) {
    }

    /** Result of converting an enquiry -- both the newly created student and the now-WON enquiry. */
    record EnquiryConversionResult(StudentResponse student, EnquiryResponse enquiry) {
    }
}
