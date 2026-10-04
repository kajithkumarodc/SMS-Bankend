package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffDirectoryDtos.StaffCardResponse;
import com.smsapp.staff.TeacherRatingDtos.RatableTeacher;
import com.smsapp.staff.TeacherRatingDtos.RatingRow;
import com.smsapp.staff.TeacherRatingDtos.RatingSummary;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.Roles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Human Resource > Teachers Rating. A student rates a teacher once, 1 to 5 stars with an optional comment; it stays
 * Pending until the school approves it, and only approved ratings count toward the teacher's average. Only teachers
 * (staff with the TEACHER role) can be rated.
 */
@Service
public class TeacherRatingService {

    private final TeacherRatingRepository ratingRepository;
    private final StaffProfileRepository profileRepository;
    private final StaffDirectoryService directoryService;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public TeacherRatingService(TeacherRatingRepository ratingRepository, StaffProfileRepository profileRepository,
                                StaffDirectoryService directoryService, StudentRepository studentRepository,
                                AuditService auditService) {
        this.ratingRepository = ratingRepository;
        this.profileRepository = profileRepository;
        this.directoryService = directoryService;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    // --- The school's side ---------------------------------------------------------------------------

    /** Every rating (or only {@code status}'s), ordered by teacher Staff ID, newest first within a teacher. */
    @Transactional(readOnly = true)
    public List<RatingRow> list(String status) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        List<TeacherRating> ratings = wanted == null ? ratingRepository.findAllByOrderByCreatedAtDesc()
                : ratingRepository.findByStatusOrderByCreatedAtDesc(wanted);
        Map<UUID, StaffProfile> profiles = profileRepository.findAll().stream()
                .collect(Collectors.toMap(StaffProfile::getId, Function.identity()));
        Map<UUID, String> names = directoryService.list(null, null, "ACTIVE").stream()
                .collect(Collectors.toMap(StaffCardResponse::id, StaffCardResponse::fullName));
        Map<UUID, Student> students = studentRepository.findAllById(ratings.stream().map(TeacherRating::getStudentId).distinct().toList())
                .stream().collect(Collectors.toMap(Student::getId, Function.identity()));
        return ratings.stream()
                .map(r -> {
                    StaffProfile profile = profiles.get(r.getStaffProfileId());
                    Student student = students.get(r.getStudentId());
                    return new RatingRow(r.getId(), r.getStaffProfileId(), profile == null ? null : profile.getEmployeeCode(),
                            names.get(r.getStaffProfileId()) != null ? names.get(r.getStaffProfileId())
                                    : profile == null ? null : fullNameOf(profile),
                            r.getRating(), r.getComment(), r.getStatus(), r.getStudentId(),
                            student == null ? null : student.getFullName(),
                            student == null ? null : student.getAdmissionNumber(), r.getCreatedAt());
                })
                .sorted(Comparator.comparing((RatingRow r) -> r.staffId() == null ? "" : r.staffId(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RatingRow::createdAt, Comparator.reverseOrder()))
                .toList();
    }

    /** Approves a rating so it counts. @throws ApiException 404 if there is no such rating. */
    @Transactional
    public void approve(UUID id, UUID approverUserId) {
        TeacherRating rating = require(id);
        if (TeacherRating.APPROVED.equals(rating.getStatus())) {
            return;
        }
        rating.setStatus(TeacherRating.APPROVED);
        rating.setApprovedByUserId(approverUserId);
        rating.setApprovedAt(OffsetDateTime.now());
        ratingRepository.save(rating);
        auditService.log(AuditActions.TEACHER_RATING_APPROVED, AuditActions.TEACHER_RATING, id,
                java.util.Map.of("staffProfileId", rating.getStaffProfileId().toString(), "rating", rating.getRating()));
    }

    /** @throws ApiException 404 if there is no such rating. */
    @Transactional
    public void delete(UUID id) {
        TeacherRating rating = require(id);
        ratingRepository.delete(rating);
        auditService.log(AuditActions.TEACHER_RATING_DELETED, AuditActions.TEACHER_RATING, id,
                java.util.Map.of("staffProfileId", rating.getStaffProfileId().toString()));
    }

    /** The average (one decimal) and count of a teacher's approved ratings. @throws ApiException 404 if no such staff member. */
    @Transactional(readOnly = true)
    public RatingSummary summary(UUID staffProfileId) {
        if (!profileRepository.existsById(staffProfileId)) {
            throw new ApiException("Staff member not found", HttpStatus.NOT_FOUND);
        }
        List<TeacherRating> approved = ratingRepository.findByStaffProfileIdAndStatus(staffProfileId, TeacherRating.APPROVED);
        if (approved.isEmpty()) {
            return new RatingSummary(staffProfileId, null, 0);
        }
        BigDecimal sum = BigDecimal.valueOf(approved.stream().mapToInt(TeacherRating::getRating).sum());
        return new RatingSummary(staffProfileId, sum.divide(BigDecimal.valueOf(approved.size()), 1, RoundingMode.HALF_UP),
                approved.size());
    }

    // --- The student's side --------------------------------------------------------------------------

    /** The active teachers the student can rate, each with the student's own rating of them (if any). */
    @Transactional(readOnly = true)
    public List<RatableTeacher> teachersFor(UUID studentUserId) {
        Student student = ownStudent(studentUserId);
        Map<UUID, TeacherRating> mine = ratingRepository.findByStudentId(student.getId()).stream()
                .collect(Collectors.toMap(TeacherRating::getStaffProfileId, Function.identity()));
        return directoryService.list(null, null, StaffDirectoryService.ACTIVE).stream()
                .filter(s -> Roles.TEACHER.equals(s.roleName()))
                .map(s -> {
                    TeacherRating rating = mine.get(s.id());
                    return new RatableTeacher(s.id(), s.staffId(), s.fullName(), s.designationName(), s.departmentName(),
                            s.hasPhoto(), rating == null ? null : rating.getRating(), rating == null ? null : rating.getComment(),
                            rating == null ? null : rating.getStatus());
                }).toList();
    }

    /**
     * A student rates a teacher; the rating starts Pending.
     *
     * @throws ApiException 404 if no student record is linked to the login or no such teacher, 400 if the staff member
     *         is not an active teacher, 409 if the student already rated this teacher.
     */
    @Transactional
    public TeacherRating submit(UUID studentUserId, UUID staffProfileId, int stars, String comment) {
        Student student = ownStudent(studentUserId);
        boolean isTeacher = directoryService.list(null, null, StaffDirectoryService.ACTIVE).stream()
                .anyMatch(s -> s.id().equals(staffProfileId) && Roles.TEACHER.equals(s.roleName()));
        if (!isTeacher) {
            if (!profileRepository.existsById(staffProfileId)) {
                throw new ApiException("Teacher not found", HttpStatus.NOT_FOUND);
            }
            throw new ApiException("Only teachers can be rated", HttpStatus.BAD_REQUEST);
        }
        if (ratingRepository.existsByStaffProfileIdAndStudentId(staffProfileId, student.getId())) {
            throw new ApiException("You have already rated this teacher", HttpStatus.CONFLICT);
        }
        TeacherRating rating = new TeacherRating();
        rating.setStaffProfileId(staffProfileId);
        rating.setStudentId(student.getId());
        rating.setRating(stars);
        rating.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        rating.setStatus(TeacherRating.PENDING);
        TeacherRating saved;
        try {
            saved = ratingRepository.saveAndFlush(rating);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("You have already rated this teacher", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.TEACHER_RATING_SUBMITTED, AuditActions.TEACHER_RATING, saved.getId(),
                java.util.Map.of("staffProfileId", staffProfileId.toString(), "rating", stars));
        return saved;
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private Student ownStudent(UUID studentUserId) {
        return studentRepository.findByStudentUserId(studentUserId)
                .orElseThrow(() -> new ApiException("No student record is linked to your account", HttpStatus.NOT_FOUND));
    }

    private TeacherRating require(UUID id) {
        return ratingRepository.findById(id).orElseThrow(() -> new ApiException("Rating not found", HttpStatus.NOT_FOUND));
    }

    private static String fullNameOf(StaffProfile profile) {
        String last = profile.getLastName();
        return (profile.getFirstName() == null ? profile.getEmployeeCode() : profile.getFirstName()) + (last == null ? "" : " " + last);
    }
}
