package com.smsapp.staff;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A staff member's HR profile -- department/designation/salary on top of their
 * plain {@code users} account. {@code user_id} is unique (V18): one profile per user.
 */
@Entity
@Table(name = "staff_profiles")
@Getter
@Setter
@NoArgsConstructor
public class StaffProfile extends UuidEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "employee_code", nullable = false, length = 50)
    private String employeeCode;

    @Column(length = 200)
    private String department;

    @Column(length = 200)
    private String designation;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Column(name = "salary_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal salaryAmount;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "designation_id")
    private UUID designationId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "father_name", length = 200)
    private String fatherName;

    @Column(name = "mother_name", length = 200)
    private String motherName;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "date_of_leaving")
    private LocalDate dateOfLeaving;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "emergency_contact_number", length = 30)
    private String emergencyContactNumber;

    @Column(name = "marital_status", length = 20)
    private String maritalStatus;

    @Column(name = "current_address", length = 500)
    private String currentAddress;

    @Column(name = "permanent_address", length = 500)
    private String permanentAddress;

    @Column(name = "qualification", length = 500)
    private String qualification;

    @Column(name = "work_experience", length = 500)
    private String workExperience;

    @Column(name = "note")
    private String note;

    @Column(name = "pan_number", length = 20)
    private String panNumber;

    @Column(name = "epf_no", length = 50)
    private String epfNo;

    @Column(name = "contract_type", length = 20)
    private String contractType;

    @Column(name = "work_shift", length = 100)
    private String workShift;

    @Column(name = "work_location", length = 100)
    private String workLocation;

    @Column(name = "medical_leave")
    private Integer medicalLeave;

    @Column(name = "casual_leave")
    private Integer casualLeave;

    @Column(name = "maternity_leave")
    private Integer maternityLeave;

    @Column(name = "sick_leave")
    private Integer sickLeave;

    @Column(name = "mandatory_leave")
    private Integer mandatoryLeave;

    @Column(name = "account_title", length = 200)
    private String accountTitle;

    @Column(name = "bank_account_number", length = 40)
    private String bankAccountNumber;

    @Column(name = "bank_name", length = 150)
    private String bankName;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    @Column(name = "bank_branch_name", length = 150)
    private String bankBranchName;

    @Column(name = "facebook_url", length = 300)
    private String facebookUrl;

    @Column(name = "twitter_url", length = 300)
    private String twitterUrl;

    @Column(name = "linkedin_url", length = 300)
    private String linkedinUrl;

    @Column(name = "instagram_url", length = 300)
    private String instagramUrl;

    @Column(name = "photo_original_filename", length = 255)
    private String photoOriginalFilename;

    @Column(name = "photo_stored_filename", length = 100)
    private String photoStoredFilename;

    @Column(name = "photo_content_type", length = 150)
    private String photoContentType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean hasPhoto() {
        return photoStoredFilename != null;
    }
}
