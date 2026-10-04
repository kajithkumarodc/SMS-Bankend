package com.smsapp.staff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Staff Directory API (Human Resource > Staff Directory). */
public final class StaffDirectoryDtos {

    /** Same rule as the Front Office phone fields. */
    static final String PHONE_PATTERN = "^\\+?[0-9][0-9 ()-]{4,28}[0-9]$";

    private StaffDirectoryDtos() {
    }

    /**
     * Body for {@code POST /api/v1/staff-members} and {@code PUT /api/v1/staff-members/{id}} -- the Add Staff
     * form. Photo and documents are uploaded separately. On update the Staff ID and email (the login) must
     * match the existing ones.
     */
    record StaffRequest(
            @NotBlank @Size(max = 50) String staffId,
            @NotNull UUID roleId,
            UUID designationId,
            UUID departmentId,
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 200) String fatherName,
            @Size(max = 200) String motherName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "(?i)MALE|FEMALE|OTHER", message = "must be Male, Female or Other") String gender,
            @NotNull @Past LocalDate dateOfBirth,
            LocalDate dateOfJoining,
            @Size(max = 30) @Pattern(regexp = PHONE_PATTERN, message = "must be a valid phone number") String phone,
            @Size(max = 30) @Pattern(regexp = PHONE_PATTERN, message = "must be a valid phone number")
            String emergencyContactNumber,
            @Pattern(regexp = "(?i)SINGLE|MARRIED|WIDOWED|SEPARATED|NOT_SPECIFIED",
                    message = "must be Single, Married, Widowed, Separated or Not Specified") String maritalStatus,
            @Size(max = 500) String currentAddress,
            @Size(max = 500) String permanentAddress,
            @Size(max = 500) String qualification,
            @Size(max = 500) String workExperience,
            @Size(max = 2000) String note,
            @NotBlank @Size(max = 20) String panNumber,
            @Size(max = 50) String epfNo,
            @PositiveOrZero @jakarta.validation.constraints.Digits(integer = 10, fraction = 2) BigDecimal basicSalary,
            @Pattern(regexp = "(?i)PERMANENT|PROBATION", message = "must be Permanent or Probation") String contractType,
            @Size(max = 100) String workShift,
            @Size(max = 100) String workLocation,
            @PositiveOrZero Integer medicalLeave,
            @PositiveOrZero Integer casualLeave,
            @PositiveOrZero Integer maternityLeave,
            @PositiveOrZero Integer sickLeave,
            @PositiveOrZero Integer mandatoryLeave,
            @Size(max = 200) String accountTitle,
            @Size(max = 40) String bankAccountNumber,
            @Size(max = 150) String bankName,
            @Size(max = 20) String ifscCode,
            @Size(max = 150) String bankBranchName,
            @Size(max = 300) String facebookUrl,
            @Size(max = 300) String twitterUrl,
            @Size(max = 300) String linkedinUrl,
            @Size(max = 300) String instagramUrl) {
    }

    record DocumentInfo(String kind, String title, String fileName, String contentType, long sizeBytes) {
    }

    /** One staff member, everything on the Add Staff form. */
    public record StaffMemberResponse(
            UUID id,
            UUID userId,
            String staffId,
            String firstName,
            String lastName,
            String fullName,
            String fatherName,
            String motherName,
            String email,
            UUID roleId,
            String roleName,
            UUID designationId,
            String designationName,
            UUID departmentId,
            String departmentName,
            String gender,
            LocalDate dateOfBirth,
            LocalDate dateOfJoining,
            LocalDate dateOfLeaving,
            String phone,
            String emergencyContactNumber,
            String maritalStatus,
            String currentAddress,
            String permanentAddress,
            String qualification,
            String workExperience,
            String note,
            String panNumber,
            String epfNo,
            BigDecimal basicSalary,
            String contractType,
            String workShift,
            String workLocation,
            Integer medicalLeave,
            Integer casualLeave,
            Integer maternityLeave,
            Integer sickLeave,
            Integer mandatoryLeave,
            String accountTitle,
            String bankAccountNumber,
            String bankName,
            String ifscCode,
            String bankBranchName,
            String facebookUrl,
            String twitterUrl,
            String linkedinUrl,
            String instagramUrl,
            boolean hasPhoto,
            List<DocumentInfo> documents,
            String status,
            OffsetDateTime createdAt) {
    }

    /** A staff member as a card/row in the directory. */
    public record StaffCardResponse(
            UUID id,
            UUID userId,
            String staffId,
            String fullName,
            String phone,
            String email,
            String workLocation,
            String departmentName,
            String designationName,
            UUID roleId,
            String roleName,
            LocalDate dateOfJoining,
            boolean hasPhoto,
            String status) {
    }

    /** Returned once on create: the new staff member and the login's temporary password. */
    record CreatedStaffResponse(StaffMemberResponse staff, String temporaryPassword) {
    }

    public record LookupOption(UUID id, String name) {
    }

    /** The Role / Designation / Department choices on the Staff Directory pages. */
    public record StaffOptionsResponse(List<LookupOption> roles, List<LookupOption> designations,
                                List<LookupOption> departments) {
    }

    record ImportRowResult(int row, String staffId, String name, String status, List<String> messages,
                           String email, String temporaryPassword) {
    }

    record ImportResult(int imported, int skipped, List<ImportRowResult> rows) {
    }
}
