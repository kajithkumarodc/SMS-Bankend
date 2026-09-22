package com.smsapp.student;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "students")
@Getter
@Setter
@NoArgsConstructor
public class Student extends UuidEntity {

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    /** Always derived server-side from {@link #firstName}/{@link #middleName}/{@link #lastName} -- see StudentService. */
    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "admission_number", nullable = false, length = 60)
    private String admissionNumber;

    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(name = "guardian_contact", length = 50)
    private String guardianContact;

    @Column(name = "blood_group", length = 5)
    private String bloodGroup;

    @Column(name = "guardian_relationship", length = 20)
    private String guardianRelationship;

    @Column(name = "guardian_phone", length = 30)
    private String guardianPhone;

    @Column(name = "guardian_email", length = 200)
    private String guardianEmail;

    @Column(length = 10)
    private String gender;

    @Column(name = "admission_date")
    private LocalDate admissionDate;

    @Column(name = "roll_number", length = 20)
    private String rollNumber;

    @Column(length = 100)
    private String nationality;

    @Column(name = "mother_tongue", length = 100)
    private String motherTongue;

    @Column(name = "guardian_alternate_phone", length = 30)
    private String guardianAlternatePhone;

    @Column(name = "guardian_occupation", length = 200)
    private String guardianOccupation;

    @Column(name = "father_name", length = 200)
    private String fatherName;

    @Column(name = "father_mobile", length = 30)
    private String fatherMobile;

    @Column(name = "father_email", length = 200)
    private String fatherEmail;

    @Column(name = "father_occupation", length = 200)
    private String fatherOccupation;

    @Column(name = "mother_name", length = 200)
    private String motherName;

    @Column(name = "mother_mobile", length = 30)
    private String motherMobile;

    @Column(name = "mother_email", length = 200)
    private String motherEmail;

    @Column(name = "mother_occupation", length = 200)
    private String motherOccupation;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_relationship", length = 30)
    private String emergencyContactRelationship;

    @Column(name = "emergency_contact_mobile", length = 30)
    private String emergencyContactMobile;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String pincode;

    @Column(name = "current_country", length = 100)
    private String currentCountry;

    @Column(name = "permanent_address_line1", length = 255)
    private String permanentAddressLine1;

    @Column(name = "permanent_address_line2", length = 255)
    private String permanentAddressLine2;

    @Column(name = "permanent_city", length = 100)
    private String permanentCity;

    @Column(name = "permanent_state", length = 100)
    private String permanentState;

    @Column(name = "permanent_country", length = 100)
    private String permanentCountry;

    @Column(name = "permanent_pincode", length = 10)
    private String permanentPincode;

    @Column(name = "emergency_contact_alternate_mobile", length = 30)
    private String emergencyContactAlternateMobile;

    @Column(name = "emergency_contact_address")
    private String emergencyContactAddress;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(length = 100)
    private String religion;

    @Column(length = 50)
    private String category;

    @Column(name = "enrollment_number", length = 60)
    private String enrollmentNumber;

    @Column(name = "previous_school_name", length = 200)
    private String previousSchoolName;

    @Column(name = "previous_school_class", length = 50)
    private String previousSchoolClass;

    @Column(name = "previous_school_admission_number", length = 60)
    private String previousSchoolAdmissionNumber;

    @Column(name = "previous_school_address")
    private String previousSchoolAddress;

    @Column(name = "transfer_certificate_number", length = 100)
    private String transferCertificateNumber;

    @Column(name = "admission_source", length = 100)
    private String admissionSource;

    @Column(name = "rte_status", nullable = false)
    private boolean rteStatus;

    /** Sibling grouping key -- students sharing a family_id are treated as siblings. Null = no linked family. */
    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "sms_notifications_enabled", nullable = false)
    private boolean smsNotificationsEnabled = true;

    @Column(name = "whatsapp_notifications_enabled", nullable = false)
    private boolean whatsappNotificationsEnabled = true;

    @Column(name = "email_notifications_enabled", nullable = false)
    private boolean emailNotificationsEnabled = true;

    @Column(name = "preferred_language", nullable = false, length = 20)
    private String preferredLanguage = "ENGLISH";

    @Column(nullable = false, length = 30)
    private String status;

    /** Section the student is assigned to, or null if not assigned yet. */
    @Column(name = "section_id")
    private UUID sectionId;

    /** Transport route the student uses, or null if they don't use school transport. */
    @Column(name = "transport_route_id")
    private UUID transportRouteId;

    /** Hostel room the student is allocated to, or null if they are a day scholar. */
    @Column(name = "hostel_room_id")
    private UUID hostelRoomId;

    /** The PARENT user account linked as this student's guardian, or null. */
    @Column(name = "guardian_user_id")
    private UUID guardianUserId;

    /** This student's own STUDENT-role login, or null if they have none. */
    @Column(name = "student_user_id")
    private UUID studentUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
