package com.smsapp.student;

import com.smsapp.common.TenantScopedEntity;
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
public class Student extends TenantScopedEntity {

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "admission_number", nullable = false, length = 60)
    private String admissionNumber;

    @Column(name = "guardian_name", length = 200)
    private String guardianName;

    @Column(name = "guardian_contact", length = 50)
    private String guardianContact;

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
