package com.smsapp.admission;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A public applicant's submission (plan part 1). Every student-information/guardian/previous-school
 * field here exists for exactly one reason: so approval can build a Phase 3
 * {@code StudentDtos.CreateStudentRequest} automatically, with no operator re-typing -- this is
 * deliberately the same field set as that request, never a second student model.
 */
@Entity
@Table(name = "admission_applications")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionApplication extends UuidEntity {

    @Column(name = "application_number", nullable = false, length = 30, updatable = false)
    private String applicationNumber;

    @Column(name = "admission_cycle_id", nullable = false, updatable = false)
    private UUID admissionCycleId;

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    /** One of {@link AdmissionApplicationStatus}. */
    @Column(nullable = false, length = 20)
    private String status = AdmissionApplicationStatus.SUBMITTED;

    // --- student information ---
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(length = 10)
    private String gender;

    @Column(name = "blood_group", length = 5)
    private String bloodGroup;

    @Column(length = 100)
    private String nationality;

    @Column(length = 100)
    private String religion;

    @Column(name = "mother_tongue", length = 100)
    private String motherTongue;

    @Column(length = 50)
    private String category;

    // --- admission details ---
    @Column(name = "applying_class_id")
    private UUID applyingClassId;

    @Column(name = "previous_school_name", length = 200)
    private String previousSchoolName;

    @Column(name = "previous_school_class", length = 50)
    private String previousSchoolClass;

    @Column(name = "previous_school_admission_number", length = 60)
    private String previousSchoolAdmissionNumber;

    @Column(name = "previous_school_address", length = 500)
    private String previousSchoolAddress;

    @Column(name = "admission_source", length = 100)
    private String admissionSource;

    // --- guardian ---
    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(name = "guardian_relationship", length = 20)
    private String guardianRelationship;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    @Column(name = "guardian_alternate_phone", length = 20)
    private String guardianAlternatePhone;

    @Column(name = "guardian_email", length = 200)
    private String guardianEmail;

    @Column(name = "guardian_occupation", length = 200)
    private String guardianOccupation;

    @Column(name = "father_name", length = 200)
    private String fatherName;

    @Column(name = "father_mobile", length = 20)
    private String fatherMobile;

    @Column(name = "father_email", length = 200)
    private String fatherEmail;

    @Column(name = "father_occupation", length = 200)
    private String fatherOccupation;

    @Column(name = "mother_name", length = 200)
    private String motherName;

    @Column(name = "mother_mobile", length = 20)
    private String motherMobile;

    @Column(name = "mother_email", length = 200)
    private String motherEmail;

    @Column(name = "mother_occupation", length = 200)
    private String motherOccupation;

    // --- address ---
    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 100)
    private String country;

    @Column(length = 10)
    private String pincode;

    // --- review / conversion ---
    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @Column(name = "reviewer_notes", length = 2000)
    private String reviewerNotes;

    /** Set once, at approval. Never a second conversion (plan part 12 -- also a DB UNIQUE constraint). */
    @Column(name = "converted_student_id")
    private UUID convertedStudentId;

    @Column(name = "converted_guardian_user_id")
    private UUID convertedGuardianUserId;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
