package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.VisitorDtos.MeetingPerson;
import com.smsapp.frontoffice.VisitorDtos.VisitorRequest;
import com.smsapp.frontoffice.VisitorDtos.VisitorResponse;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import com.smsapp.staff.StaffProfileStatus;
import com.smsapp.student.Student;
import com.smsapp.student.StudentDocumentService;
import com.smsapp.student.StudentRepository;
import com.smsapp.student.StudentStatus;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Front Office / Visitor Book. Attached documents are stored like student documents
 * ({@link StudentDocumentService}): on local disk under {@code app.storage.base-dir}, with a random
 * on-disk name, the same content-type allow-list and 10 MB cap, and only readable through the
 * permission-checked download endpoint.
 */
@Service
public class VisitorService {

    private static final int MEETING_OPTION_LIMIT = 20;

    private final VisitorRepository visitorRepository;
    private final FrontOfficePurposeRepository purposeRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Path baseDir;

    public VisitorService(VisitorRepository visitorRepository, FrontOfficePurposeRepository purposeRepository,
                          StaffProfileRepository staffProfileRepository, StudentRepository studentRepository,
                          UserRepository userRepository, AuditService auditService,
                          @Value("${app.storage.base-dir}") String baseDir) {
        this.visitorRepository = visitorRepository;
        this.purposeRepository = purposeRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /**
     * @throws ApiException 400 if the meeting-with type/person or the times are invalid, 404 if the
     *         purpose, staff member or student doesn't exist.
     */
    @Transactional
    public Visitor create(VisitorRequest request, UUID createdByUserId) {
        Visitor visitor = new Visitor();
        apply(visitor, request);
        visitor.setCreatedByUserId(createdByUserId);
        Visitor saved = visitorRepository.save(visitor);
        auditService.log(AuditActions.VISITOR_CREATED, AuditActions.VISITOR, saved.getId(),
                Map.of("visitorName", saved.getVisitorName()));
        return saved;
    }

    /** Same rules as {@link #create}; the attachment is left as it is. @throws ApiException 404 if no such visitor. */
    @Transactional
    public Visitor update(UUID id, VisitorRequest request) {
        Visitor visitor = requireVisitor(id);
        apply(visitor, request);
        Visitor saved = visitorRepository.save(visitor);
        auditService.log(AuditActions.VISITOR_UPDATED, AuditActions.VISITOR, id,
                Map.of("visitorName", saved.getVisitorName()));
        return saved;
    }

    /** Deletes the entry and its attached file. @throws ApiException 404 if no such visitor. */
    @Transactional
    public void delete(UUID id) {
        Visitor visitor = requireVisitor(id);
        deleteStoredFile(visitor);
        try {
            Files.deleteIfExists(visitorDir(id)); // only succeeds once it's empty, which it now is
        } catch (IOException e) {
            // Not worth failing the delete over an empty folder -- it holds no data.
        }
        visitorRepository.delete(visitor);
        auditService.log(AuditActions.VISITOR_DELETED, AuditActions.VISITOR, id,
                Map.of("visitorName", visitor.getVisitorName()));
    }

    @Transactional(readOnly = true)
    public Visitor get(UUID id) {
        return requireVisitor(id);
    }

    /**
     * Search, paginated. {@code query} matches visitor name, phone, ID card or note; {@code from}/{@code to}
     * bound the visit date (either may be null).
     */
    @Transactional(readOnly = true)
    public Page<Visitor> search(String query, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Visitor> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("visitorName")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("idCard")), like),
                        cb.like(cb.lower(root.get("note")), like)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("visitDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("visitDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return visitorRepository.findAll(spec, pageable);
    }

    // --- Attachment ---------------------------------------------------------------

    /**
     * Attaches (or replaces) the visitor's document.
     *
     * @throws ApiException 404 if no such visitor, 400 if the file is empty, over 10 MB or not an allowed type.
     */
    @Transactional
    public Visitor attach(UUID id, MultipartFile file) {
        Visitor visitor = requireVisitor(id);
        if (file == null || file.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > StudentDocumentService.maxFileSizeBytes()) {
            throw new ApiException("The file exceeds the 10 MB limit", HttpStatus.BAD_REQUEST);
        }
        String extension = StudentDocumentService.allowedExtensionFor(file.getContentType());
        if (extension == null) {
            throw new ApiException("Unsupported file type -- allowed: PDF, JPG, PNG, WEBP, DOC, DOCX",
                    HttpStatus.BAD_REQUEST);
        }

        Path dir = visitorDir(id);
        String storedFilename = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(storedFilename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }
        deleteStoredFile(visitor);

        visitor.setAttachmentOriginalFilename(sanitizeDisplayName(file.getOriginalFilename()));
        visitor.setAttachmentStoredFilename(storedFilename);
        visitor.setAttachmentContentType(file.getContentType());
        visitor.setAttachmentSizeBytes(file.getSize());
        Visitor saved = visitorRepository.save(visitor);
        auditService.log(AuditActions.VISITOR_ATTACHMENT_UPLOADED, AuditActions.VISITOR, id,
                Map.of("fileName", saved.getAttachmentOriginalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if no such visitor or it has no attachment. */
    @Transactional
    public Visitor removeAttachment(UUID id) {
        Visitor visitor = requireVisitor(id);
        if (!visitor.hasAttachment()) {
            throw new ApiException("This visitor has no attached document", HttpStatus.NOT_FOUND);
        }
        deleteStoredFile(visitor);
        visitor.setAttachmentOriginalFilename(null);
        visitor.setAttachmentStoredFilename(null);
        visitor.setAttachmentContentType(null);
        visitor.setAttachmentSizeBytes(null);
        Visitor saved = visitorRepository.save(visitor);
        auditService.log(AuditActions.VISITOR_ATTACHMENT_REMOVED, AuditActions.VISITOR, id, Map.of());
        return saved;
    }

    /** The attached file for a download response. @throws ApiException 404 if there's no attachment or the file is gone. */
    @Transactional(readOnly = true)
    public Resource loadAttachment(Visitor visitor) {
        if (!visitor.hasAttachment()) {
            throw new ApiException("This visitor has no attached document", HttpStatus.NOT_FOUND);
        }
        Path file = visitorDir(visitor.getId()).resolve(visitor.getAttachmentStoredFilename());
        if (!Files.isReadable(file)) {
            throw new ApiException("Attached document not found", HttpStatus.NOT_FOUND);
        }
        return new FileSystemResource(file);
    }

    // --- Meeting With picker --------------------------------------------------------

    /**
     * Active staff members or students a visitor can be recorded as meeting, matching {@code query} on name
     * or employee code / admission number. At most 20, alphabetical.
     *
     * @throws ApiException 400 if {@code type} isn't STAFF or STUDENT.
     */
    @Transactional(readOnly = true)
    public List<MeetingPerson> meetingOptions(String type, String query) {
        String normalized = requireMeetingType(type);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        if (MeetingWithType.STAFF.equals(normalized)) {
            List<StaffProfile> profiles = staffProfileRepository.findAllByOrderByEmployeeCode().stream()
                    .filter(p -> StaffProfileStatus.ACTIVE.equals(p.getStatus()))
                    .toList();
            Map<UUID, String> names = userNames(profiles.stream().map(StaffProfile::getUserId).collect(Collectors.toSet()));
            return profiles.stream()
                    .map(p -> new MeetingPerson(p.getId(), MeetingWithType.STAFF, names.getOrDefault(p.getUserId(), ""),
                            p.getEmployeeCode()))
                    .filter(p -> needle.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(needle)
                            || p.code().toLowerCase(Locale.ROOT).contains(needle))
                    .sorted(Comparator.comparing(MeetingPerson::name, String.CASE_INSENSITIVE_ORDER))
                    .limit(MEETING_OPTION_LIMIT)
                    .toList();
        }

        Specification<Student> spec = (root, criteriaQuery, cb) -> {
            Predicate active = cb.equal(root.get("status"), StudentStatus.ACTIVE);
            if (needle.isEmpty()) {
                return active;
            }
            String like = "%" + needle + "%";
            return cb.and(active, cb.or(cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("admissionNumber")), like)));
        };
        return studentRepository.findAll(spec, PageRequest.of(0, MEETING_OPTION_LIMIT, Sort.by("fullName")))
                .map(s -> new MeetingPerson(s.getId(), MeetingWithType.STUDENT, s.getFullName(), s.getAdmissionNumber()))
                .getContent();
    }

    // --- Response mapping -----------------------------------------------------------

    @Transactional(readOnly = true)
    public VisitorResponse toResponse(Visitor visitor) {
        return toResponses(List.of(visitor)).get(0);
    }

    /** Maps a page of visitors with one lookup per referenced table rather than several per row. */
    @Transactional(readOnly = true)
    public List<VisitorResponse> toResponses(List<Visitor> visitors) {
        Map<UUID, String> purposes = purposeRepository.findAllById(ids(visitors, Visitor::getPurposeId)).stream()
                .collect(Collectors.toMap(FrontOfficePurpose::getId, FrontOfficePurpose::getName));

        Map<UUID, MeetingPerson> people = new HashMap<>();
        List<StaffProfile> profiles = staffProfileRepository.findAllById(ids(visitors, Visitor::getStaffProfileId));
        Map<UUID, String> staffNames = userNames(profiles.stream().map(StaffProfile::getUserId).collect(Collectors.toSet()));
        profiles.forEach(p -> people.put(p.getId(), new MeetingPerson(p.getId(), MeetingWithType.STAFF,
                staffNames.get(p.getUserId()), p.getEmployeeCode())));
        studentRepository.findAllById(ids(visitors, Visitor::getStudentId)).forEach(s -> people.put(s.getId(),
                new MeetingPerson(s.getId(), MeetingWithType.STUDENT, s.getFullName(), s.getAdmissionNumber())));

        return visitors.stream()
                .map(v -> VisitorResponse.from(v, purposes.get(v.getPurposeId()), people.get(
                        MeetingWithType.STAFF.equals(v.getMeetingWithType()) ? v.getStaffProfileId() : v.getStudentId())))
                .toList();
    }

    // --- Helpers ----------------------------------------------------------------------

    private void apply(Visitor visitor, VisitorRequest request) {
        if (!purposeRepository.existsById(request.purposeId())) {
            throw new ApiException("Purpose not found", HttpStatus.NOT_FOUND);
        }
        String type = requireMeetingType(request.meetingWithType());
        if (MeetingWithType.STAFF.equals(type)) {
            if (request.staffProfileId() == null) {
                throw new ApiException("Choose the staff member the visitor is meeting", HttpStatus.BAD_REQUEST);
            }
            if (!staffProfileRepository.existsById(request.staffProfileId())) {
                throw new ApiException("Staff member not found", HttpStatus.NOT_FOUND);
            }
            visitor.setStaffProfileId(request.staffProfileId());
            visitor.setStudentId(null);
        } else {
            if (request.studentId() == null) {
                throw new ApiException("Choose the student the visitor is meeting", HttpStatus.BAD_REQUEST);
            }
            if (!studentRepository.existsById(request.studentId())) {
                throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
            }
            visitor.setStudentId(request.studentId());
            visitor.setStaffProfileId(null);
        }
        if (request.inTime() != null && request.outTime() != null && request.outTime().isBefore(request.inTime())) {
            throw new ApiException("Out time can't be before in time", HttpStatus.BAD_REQUEST);
        }

        visitor.setPurposeId(request.purposeId());
        visitor.setMeetingWithType(type);
        visitor.setVisitorName(request.visitorName().trim());
        visitor.setPhone(blankToNull(request.phone()));
        visitor.setIdCard(blankToNull(request.idCard()));
        visitor.setNumberOfPersons(request.numberOfPersons() == null ? null : request.numberOfPersons().shortValue());
        visitor.setVisitDate(request.visitDate());
        visitor.setInTime(request.inTime());
        visitor.setOutTime(request.outTime());
        visitor.setNote(blankToNull(request.note()));
    }

    private static String requireMeetingType(String raw) {
        String type = MeetingWithType.normalizeOrNull(raw);
        if (type == null) {
            throw new ApiException("Meeting with must be STAFF or STUDENT", HttpStatus.BAD_REQUEST);
        }
        return type;
    }

    private Map<UUID, String> userNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    }

    private static Set<UUID> ids(List<Visitor> visitors, Function<Visitor, UUID> getter) {
        return visitors.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** {@code <base-dir>/visitors/<visitorId>} -- the id is a UUID, never caller-supplied text. */
    private Path visitorDir(UUID visitorId) {
        Path dir = baseDir.resolve("visitors").resolve(visitorId.toString()).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new ApiException("Invalid storage path", HttpStatus.BAD_REQUEST);
        }
        return dir;
    }

    private void deleteStoredFile(Visitor visitor) {
        if (!visitor.hasAttachment()) {
            return;
        }
        try {
            Files.deleteIfExists(visitorDir(visitor.getId()).resolve(visitor.getAttachmentStoredFilename()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the stored file", e);
        }
    }

    private Visitor requireVisitor(UUID id) {
        return visitorRepository.findById(id)
                .orElseThrow(() -> new ApiException("Visitor not found", HttpStatus.NOT_FOUND));
    }

    /** Strips any path from a client-supplied filename -- display only, never used as a disk path. */
    private static String sanitizeDisplayName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "document";
        }
        String name = rawName.replace('\\', '/');
        String base = name.substring(name.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
