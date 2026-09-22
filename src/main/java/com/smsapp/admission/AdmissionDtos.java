package com.smsapp.admission;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the admissions API. Entities are never exposed directly. */
final class AdmissionDtos {

    private static final String MOBILE_REGEX = "\\+?[0-9]{10,15}";
    private static final String MOBILE_MESSAGE = "Enter a valid mobile number (10-15 digits, optional +)";
    private static final String RELATIONSHIP_REGEX = "FATHER|MOTHER|GUARDIAN|OTHER";
    private static final String RELATIONSHIP_MESSAGE = "Not a recognized relationship";

    private AdmissionDtos() {
    }

    // --- Admission cycles --------------------------------------------

    record CreateAdmissionCycleRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicYearId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @NotNull LocalDate openDate,
            @NotNull LocalDate closeDate) {
    }

    record UpdateAdmissionCycleRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @NotNull LocalDate openDate,
            @NotNull LocalDate closeDate) {
    }

    record AdmissionCycleResponse(
            UUID id,
            UUID schoolId,
            UUID academicYearId,
            String academicYearName,
            String name,
            String description,
            LocalDate openDate,
            LocalDate closeDate,
            String status,
            OffsetDateTime createdAt) {

        static AdmissionCycleResponse from(AdmissionCycle cycle, String academicYearName) {
            return new AdmissionCycleResponse(cycle.getId(), cycle.getSchoolId(), cycle.getAcademicYearId(),
                    academicYearName, cycle.getName(), cycle.getDescription(), cycle.getOpenDate(),
                    cycle.getCloseDate(), cycle.getStatus(), cycle.getCreatedAt());
        }
    }

    /** The public application page: only what an applicant needs to know before applying. */
    record PublicOpenCycleResponse(
            UUID id,
            String name,
            String description,
            String academicYearName,
            LocalDate openDate,
            LocalDate closeDate) {
    }

    // --- Public submission ---------------------------------------------

    /**
     * Every field an approval needs to build a Phase 3 {@code CreateStudentRequest} automatically --
     * deliberately the same breadth/validation as that request (plan part 3).
     */
    record SubmitApplicationRequest(
            @NotNull UUID admissionCycleId,

            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Not a recognized gender") String gender,
            @Pattern(regexp = "A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-|UNKNOWN", message = "Not a recognized blood group")
            String bloodGroup,
            @Size(max = 100) String nationality,
            @Size(max = 100) String religion,
            @Size(max = 100) String motherTongue,
            @Size(max = 50) String category,

            UUID applyingClassId,
            @Size(max = 200) String previousSchoolName,
            @Size(max = 50) String previousSchoolClass,
            @Size(max = 60) String previousSchoolAdmissionNumber,
            @Size(max = 500) String previousSchoolAddress,

            @Size(max = 200) String guardianName,
            @Pattern(regexp = RELATIONSHIP_REGEX, message = RELATIONSHIP_MESSAGE) String guardianRelationship,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianPhone,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianAlternatePhone,
            @NotBlank @Email @Size(max = 200) String guardianEmail,
            @Size(max = 200) String guardianOccupation,

            @Size(max = 200) String fatherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String fatherMobile,
            @Email @Size(max = 200) String fatherEmail,
            @Size(max = 200) String fatherOccupation,
            @Size(max = 200) String motherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String motherMobile,
            @Email @Size(max = 200) String motherEmail,
            @Size(max = 200) String motherOccupation,

            @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 100) String country,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "Enter a valid 6-digit PIN code") String pincode) {
    }

    record SubmitApplicationResponse(String applicationNumber, String message) {
    }

    record StatusLookupRequest(
            @NotBlank @Size(max = 30) String applicationNumber,
            @NotBlank @Email @Size(max = 200) String email) {
    }

    /** Only the safe subset (plan part 15) -- no reviewer identity/notes, no internal ids. */
    record StatusLookupResponse(
            String applicationNumber,
            String admissionCycleName,
            String status,
            OffsetDateTime submittedAt,
            OffsetDateTime updatedAt,
            String message) {
    }

    // --- Admin list / detail --------------------------------------------

    record AdmissionApplicationSummaryResponse(
            UUID id,
            String applicationNumber,
            String applicantName,
            String applyingClassName,
            String guardianName,
            String guardianPhone,
            String admissionCycleName,
            OffsetDateTime submittedAt,
            String status,
            String reviewedByName,
            OffsetDateTime updatedAt) {
    }

    record AdmissionApplicationDetailResponse(
            UUID id,
            String applicationNumber,
            UUID admissionCycleId,
            String admissionCycleName,
            String status,

            String firstName,
            String middleName,
            String lastName,
            LocalDate dateOfBirth,
            String gender,
            String bloodGroup,
            String nationality,
            String religion,
            String motherTongue,
            String category,

            UUID applyingClassId,
            String applyingClassName,
            String previousSchoolName,
            String previousSchoolClass,
            String previousSchoolAdmissionNumber,
            String previousSchoolAddress,
            String admissionSource,

            String guardianName,
            String guardianRelationship,
            String guardianPhone,
            String guardianAlternatePhone,
            String guardianEmail,
            String guardianOccupation,
            String fatherName,
            String fatherMobile,
            String fatherEmail,
            String fatherOccupation,
            String motherName,
            String motherMobile,
            String motherEmail,
            String motherOccupation,

            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String country,
            String pincode,

            OffsetDateTime reviewedAt,
            String reviewedByName,
            String reviewerNotes,
            UUID convertedStudentId,
            UUID convertedGuardianUserId,

            List<AdmissionApplicationDocumentResponse> documents,
            OffsetDateTime submittedAt,
            OffsetDateTime updatedAt) {
    }

    record AdmissionApplicationDocumentResponse(
            UUID id,
            String documentType,
            String originalFilename,
            String contentType,
            long fileSizeBytes,
            OffsetDateTime uploadedAt) {

        static AdmissionApplicationDocumentResponse from(AdmissionApplicationDocument document) {
            return new AdmissionApplicationDocumentResponse(document.getId(), document.getDocumentType(),
                    document.getOriginalFilename(), document.getContentType(), document.getFileSizeBytes(),
                    document.getUploadedAt());
        }
    }

    // --- Review actions --------------------------------------------------

    record ReviewNotesRequest(@Size(max = 2000) String notes) {
    }

    record RejectApplicationRequest(@NotBlank @Size(max = 2000) String notes) {
    }

    /** What approval produced -- so the admin UI can show exactly what was created/linked (plan part 10). */
    record ApprovalResult(
            AdmissionApplicationDetailResponse application,
            UUID studentId,
            String studentAdmissionNumber,
            UUID guardianUserId,
            boolean guardianUserCreated,
            boolean portalInvitationSent) {
    }
}
