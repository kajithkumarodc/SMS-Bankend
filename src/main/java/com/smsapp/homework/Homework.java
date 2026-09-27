package com.smsapp.homework;

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

/** A piece of homework set for one section of a class, in one subject. */
@Entity
@Table(name = "homework")
@Getter
@Setter
@NoArgsConstructor
public class Homework extends UuidEntity {

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "assigned_date", nullable = false)
    private LocalDate assignedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
