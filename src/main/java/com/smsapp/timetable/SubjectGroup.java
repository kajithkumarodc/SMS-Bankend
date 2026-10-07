package com.smsapp.timetable;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The subjects a set of sections of one class study together (Academics > Subject Group). */
@Entity
@Table(name = "subject_groups")
@Getter
@Setter
@NoArgsConstructor
public class SubjectGroup extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "subject_group_sections", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "section_id")
    private Set<UUID> sectionIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "subject_group_subjects", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "subject_id")
    private Set<UUID> subjectIds = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
