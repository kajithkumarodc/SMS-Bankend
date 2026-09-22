package com.smsapp.student;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request/response payloads for the students API. Entities are never exposed directly (plan section 7.1d).
 * {@link CreateStudentRequest} and {@link StudentResponse} are {@code public} (the other DTOs here stay
 * package-private) so the Front Office enquiry-conversion flow (`com.smsapp.frontoffice.EnquiryController`)
 * can reuse the exact same admission request/response shape and validation instead of duplicating it --
 * "Convert to Student" is a real student admission, not a parallel code path.
 */
public final class StudentDtos {

    private static final String MOBILE_REGEX = "\\+?[0-9]{10,15}";
    private static final String MOBILE_MESSAGE = "Enter a valid mobile number (10-15 digits, optional +)";
    private static final String RELATIONSHIP_REGEX = "FATHER|MOTHER|GUARDIAN|OTHER";
    private static final String RELATIONSHIP_MESSAGE = "Not a recognized relationship";

    private StudentDtos() {
    }

    /**
     * The full "professional admission form" (plan Phase 3 section 1). {@code fullName} is deliberately
     * absent -- {@link StudentService} always derives it from first/middle/last, so there is exactly one
     * place a student's display name is assembled. Identification documents and uploaded files are separate
     * sub-resources ({@code /students/{id}/identifications}, {@code /students/{id}/documents}), not part of
     * this request.
     */
    public record CreateStudentRequest(
            @NotNull UUID schoolId,

            // --- personal information ---
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Not a recognized gender") String gender,
            @NotNull @Past LocalDate dateOfBirth,
            @Pattern(regexp = "A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-|UNKNOWN", message = "Not a recognized blood group")
            String bloodGroup,
            @Size(max = 100) String nationality,
            @Size(max = 100) String religion,
            @Size(max = 100) String motherTongue,
            @Size(max = 50) String category,

            // --- admission information ---
            @NotBlank @Size(max = 60) String admissionNumber,
            @Size(max = 20) String rollNumber,
            @Size(max = 60) String enrollmentNumber,
            LocalDate admissionDate,
            UUID sectionId,
            @Size(max = 200) String previousSchoolName,
            @Size(max = 50) String previousSchoolClass,
            @Size(max = 60) String previousSchoolAdmissionNumber,
            String previousSchoolAddress,
            @Size(max = 100) String transferCertificateNumber,
            @Size(max = 100) String admissionSource,
            Boolean rteStatus,

            // --- primary guardian ---
            @Size(max = 200) String guardianName,
            @Pattern(regexp = RELATIONSHIP_REGEX, message = RELATIONSHIP_MESSAGE) String guardianRelationship,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianPhone,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianAlternatePhone,
            @Email @Size(max = 200) String guardianEmail,
            @Size(max = 200) String guardianOccupation,
            @Size(max = 50) String guardianContact,

            // --- father / mother ---
            @Size(max = 200) String fatherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String fatherMobile,
            @Email @Size(max = 200) String fatherEmail,
            @Size(max = 200) String fatherOccupation,
            @Size(max = 200) String motherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String motherMobile,
            @Email @Size(max = 200) String motherEmail,
            @Size(max = 200) String motherOccupation,

            // --- emergency contact ---
            @Size(max = 200) String emergencyContactName,
            @Pattern(regexp = RELATIONSHIP_REGEX, message = RELATIONSHIP_MESSAGE) String emergencyContactRelationship,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String emergencyContactMobile,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String emergencyContactAlternateMobile,
            String emergencyContactAddress,

            // --- current address ---
            @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 100) String currentCountry,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "Enter a valid 6-digit PIN code") String pincode,

            // --- permanent address ---
            /** When true, {@link StudentService} copies the current address into the permanent fields below. */
            Boolean permanentSameAsCurrentAddress,
            @Size(max = 255) String permanentAddressLine1,
            @Size(max = 255) String permanentAddressLine2,
            @Size(max = 100) String permanentCity,
            @Size(max = 100) String permanentState,
            @Size(max = 100) String permanentCountry,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "Enter a valid 6-digit PIN code") String permanentPincode,

            /** Links this student into an existing sibling group. Null = no family group. */
            UUID familyId,

            // --- communication preferences ---
            Boolean smsNotificationsEnabled,
            Boolean whatsappNotificationsEnabled,
            Boolean emailNotificationsEnabled,
            @Pattern(regexp = "ENGLISH|TAMIL|HINDI|OTHER", message = "Not a recognized language")
            String preferredLanguage) {
    }

    /**
     * Editable fields on an existing student -- the same breadth as {@link CreateStudentRequest} minus
     * {@code schoolId} and {@code admissionNumber} (both immutable after admission, see {@link StudentService#update}).
     */
    record UpdateStudentRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Not a recognized gender") String gender,
            @Past LocalDate dateOfBirth,
            @Pattern(regexp = "A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-|UNKNOWN", message = "Not a recognized blood group")
            String bloodGroup,
            @Size(max = 100) String nationality,
            @Size(max = 100) String religion,
            @Size(max = 100) String motherTongue,
            @Size(max = 50) String category,

            @Size(max = 20) String rollNumber,
            @Size(max = 60) String enrollmentNumber,
            LocalDate admissionDate,
            @Size(max = 200) String previousSchoolName,
            @Size(max = 50) String previousSchoolClass,
            @Size(max = 60) String previousSchoolAdmissionNumber,
            String previousSchoolAddress,
            @Size(max = 100) String transferCertificateNumber,
            @Size(max = 100) String admissionSource,
            Boolean rteStatus,

            @Size(max = 200) String guardianName,
            @Pattern(regexp = RELATIONSHIP_REGEX, message = RELATIONSHIP_MESSAGE) String guardianRelationship,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianPhone,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String guardianAlternatePhone,
            @Email @Size(max = 200) String guardianEmail,
            @Size(max = 200) String guardianOccupation,
            @Size(max = 50) String guardianContact,

            @Size(max = 200) String fatherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String fatherMobile,
            @Email @Size(max = 200) String fatherEmail,
            @Size(max = 200) String fatherOccupation,
            @Size(max = 200) String motherName,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String motherMobile,
            @Email @Size(max = 200) String motherEmail,
            @Size(max = 200) String motherOccupation,

            @Size(max = 200) String emergencyContactName,
            @Pattern(regexp = RELATIONSHIP_REGEX, message = RELATIONSHIP_MESSAGE) String emergencyContactRelationship,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String emergencyContactMobile,
            @Pattern(regexp = MOBILE_REGEX, message = MOBILE_MESSAGE) String emergencyContactAlternateMobile,
            String emergencyContactAddress,

            @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 100) String currentCountry,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "Enter a valid 6-digit PIN code") String pincode,

            Boolean permanentSameAsCurrentAddress,
            @Size(max = 255) String permanentAddressLine1,
            @Size(max = 255) String permanentAddressLine2,
            @Size(max = 100) String permanentCity,
            @Size(max = 100) String permanentState,
            @Size(max = 100) String permanentCountry,
            @Pattern(regexp = "[1-9][0-9]{5}", message = "Enter a valid 6-digit PIN code") String permanentPincode,

            UUID familyId,

            Boolean smsNotificationsEnabled,
            Boolean whatsappNotificationsEnabled,
            Boolean emailNotificationsEnabled,
            @Pattern(regexp = "ENGLISH|TAMIL|HINDI|OTHER", message = "Not a recognized language")
            String preferredLanguage,

            @NotBlank String status) {
    }

    /** Body for {@code PATCH /api/v1/students/{id}/status} -- the soft-delete / reactivate toggle. */
    record ChangeStudentStatusRequest(
            @NotBlank String status) {
    }

    /** Body for {@code PATCH /api/v1/students/{id}/section} -- assign / reassign a student to a section. */
    record AssignSectionRequest(
            @NotNull UUID sectionId) {
    }

    /**
     * Body for {@code PATCH /api/v1/students/{id}/transport-route}. A null
     * {@code routeId} unassigns the student from school transport.
     */
    record AssignTransportRouteRequest(
            UUID routeId) {
    }

    /**
     * Body for {@code PATCH /api/v1/students/{id}/hostel-room}. A null {@code roomId}
     * deallocates the student from the hostel.
     */
    record AllocateHostelRoomRequest(
            UUID roomId) {
    }

    public record StudentResponse(
            UUID id,
            UUID schoolId,
            String fullName,
            String firstName,
            String middleName,
            String lastName,
            String gender,
            LocalDate dateOfBirth,
            String admissionNumber,
            LocalDate admissionDate,
            String rollNumber,
            String enrollmentNumber,
            UUID sectionId,
            String bloodGroup,
            String nationality,
            String religion,
            String motherTongue,
            String category,
            String previousSchoolName,
            String previousSchoolClass,
            String previousSchoolAdmissionNumber,
            String previousSchoolAddress,
            String transferCertificateNumber,
            String admissionSource,
            boolean rteStatus,
            String photoUrl,
            UUID familyId,

            String guardianName,
            String guardianRelationship,
            String guardianPhone,
            String guardianAlternatePhone,
            String guardianEmail,
            String guardianOccupation,
            String guardianContact,
            String fatherName,
            String fatherMobile,
            String fatherEmail,
            String fatherOccupation,
            String motherName,
            String motherMobile,
            String motherEmail,
            String motherOccupation,

            String emergencyContactName,
            String emergencyContactRelationship,
            String emergencyContactMobile,
            String emergencyContactAlternateMobile,
            String emergencyContactAddress,

            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String currentCountry,
            String pincode,
            String permanentAddressLine1,
            String permanentAddressLine2,
            String permanentCity,
            String permanentState,
            String permanentCountry,
            String permanentPincode,

            boolean smsNotificationsEnabled,
            boolean whatsappNotificationsEnabled,
            boolean emailNotificationsEnabled,
            String preferredLanguage,
            String status,
            UUID transportRouteId,
            UUID hostelRoomId,
            OffsetDateTime createdAt) {

        public static StudentResponse from(Student student) {
            return new StudentResponse(
                    student.getId(),
                    student.getSchoolId(),
                    student.getFullName(),
                    student.getFirstName(),
                    student.getMiddleName(),
                    student.getLastName(),
                    student.getGender(),
                    student.getDateOfBirth(),
                    student.getAdmissionNumber(),
                    student.getAdmissionDate(),
                    student.getRollNumber(),
                    student.getEnrollmentNumber(),
                    student.getSectionId(),
                    student.getBloodGroup(),
                    student.getNationality(),
                    student.getReligion(),
                    student.getMotherTongue(),
                    student.getCategory(),
                    student.getPreviousSchoolName(),
                    student.getPreviousSchoolClass(),
                    student.getPreviousSchoolAdmissionNumber(),
                    student.getPreviousSchoolAddress(),
                    student.getTransferCertificateNumber(),
                    student.getAdmissionSource(),
                    student.isRteStatus(),
                    student.getPhotoUrl(),
                    student.getFamilyId(),

                    student.getGuardianName(),
                    student.getGuardianRelationship(),
                    student.getGuardianPhone(),
                    student.getGuardianAlternatePhone(),
                    student.getGuardianEmail(),
                    student.getGuardianOccupation(),
                    student.getGuardianContact(),
                    student.getFatherName(),
                    student.getFatherMobile(),
                    student.getFatherEmail(),
                    student.getFatherOccupation(),
                    student.getMotherName(),
                    student.getMotherMobile(),
                    student.getMotherEmail(),
                    student.getMotherOccupation(),

                    student.getEmergencyContactName(),
                    student.getEmergencyContactRelationship(),
                    student.getEmergencyContactMobile(),
                    student.getEmergencyContactAlternateMobile(),
                    student.getEmergencyContactAddress(),

                    student.getAddressLine1(),
                    student.getAddressLine2(),
                    student.getCity(),
                    student.getState(),
                    student.getCurrentCountry(),
                    student.getPincode(),
                    student.getPermanentAddressLine1(),
                    student.getPermanentAddressLine2(),
                    student.getPermanentCity(),
                    student.getPermanentState(),
                    student.getPermanentCountry(),
                    student.getPermanentPincode(),

                    student.isSmsNotificationsEnabled(),
                    student.isWhatsappNotificationsEnabled(),
                    student.isEmailNotificationsEnabled(),
                    student.getPreferredLanguage(),
                    student.getStatus(),
                    student.getTransportRouteId(),
                    student.getHostelRoomId(),
                    student.getCreatedAt());
        }
    }
}
