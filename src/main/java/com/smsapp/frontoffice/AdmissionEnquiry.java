package com.smsapp.frontoffice;

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

@Entity
@Table(name = "admission_enquiries")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionEnquiry extends UuidEntity {

    @Column(name = "enquiry_number", nullable = false, length = 20)
    private String enquiryNumber;

    @Column(name = "applicant_name", nullable = false, length = 200)
    private String applicantName;

    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(name = "class_id")
    private UUID classId;

    @Column(name = "enquiry_date", nullable = false)
    private LocalDate enquiryDate;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "assigned_staff_user_id")
    private UUID assignedStaffUserId;

    /** Denormalized cache of the latest follow-up -- kept in sync by {@link EnquiryService#recordFollowUp}. */
    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "follow_up_notes")
    private String followUpNotes;

    @Column(nullable = false, length = 20)
    private String status;

    @Column
    private String remarks;

    /** Set once, at conversion. See {@link EnquiryService#convertToStudent}. */
    @Column(name = "converted_student_id")
    private UUID convertedStudentId;

    /** Optional link to the online application this lead turned into (plan Phase 4.5 part 17). */
    @Column(name = "admission_application_id")
    private UUID admissionApplicationId;

    @Column(nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
