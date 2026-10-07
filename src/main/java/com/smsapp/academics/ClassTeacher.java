package com.smsapp.academics;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A teacher in charge of a section (Academics > Assign Class Teacher). */
@Entity
@Table(name = "class_teachers")
@Getter
@Setter
@NoArgsConstructor
public class ClassTeacher extends UuidEntity {

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "staff_profile_id", nullable = false)
    private UUID staffProfileId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
