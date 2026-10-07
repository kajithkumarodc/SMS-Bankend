package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeacherRatingRepository extends JpaRepository<TeacherRating, UUID> {

    List<TeacherRating> findAllByOrderByCreatedAtDesc();

    List<TeacherRating> findByStatusOrderByCreatedAtDesc(String status);

    List<TeacherRating> findByStaffProfileIdAndStatus(UUID staffProfileId, String status);

    List<TeacherRating> findByStudentId(UUID studentId);

    boolean existsByStaffProfileIdAndStudentId(UUID staffProfileId, UUID studentId);

    Optional<TeacherRating> findByStaffProfileIdAndStudentId(UUID staffProfileId, UUID studentId);
}
