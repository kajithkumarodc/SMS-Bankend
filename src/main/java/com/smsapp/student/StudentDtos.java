package com.smsapp.student;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the students API. Entities are never exposed directly (plan section 7.1d). */
final class StudentDtos {

    private StudentDtos() {
    }

    record CreateStudentRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 60) String admissionNumber,
            @Past LocalDate dateOfBirth,
            @Size(max = 200) String guardianName,
            @Size(max = 50) String guardianContact) {
    }

    /**
     * Editable fields on an existing student. {@code admissionNumber} is deliberately
     * absent -- see {@link StudentService#update}.
     */
    record UpdateStudentRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 200) String guardianName,
            @Size(max = 50) String guardianContact,
            @NotBlank String status) {
    }

    /** Body for {@code PATCH /api/v1/students/{id}/status} -- the soft-delete / reactivate toggle. */
    record ChangeStudentStatusRequest(
            @NotBlank String status) {
    }

    record StudentResponse(
            UUID id,
            UUID schoolId,
            String fullName,
            String admissionNumber,
            LocalDate dateOfBirth,
            String guardianName,
            String guardianContact,
            String status,
            OffsetDateTime createdAt) {

        static StudentResponse from(Student student) {
            return new StudentResponse(
                    student.getId(),
                    student.getSchoolId(),
                    student.getFullName(),
                    student.getAdmissionNumber(),
                    student.getDateOfBirth(),
                    student.getGuardianName(),
                    student.getGuardianContact(),
                    student.getStatus(),
                    student.getCreatedAt());
        }
    }
}
