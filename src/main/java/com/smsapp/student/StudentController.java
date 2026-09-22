package com.smsapp.student;

import com.smsapp.hostel.HostelService;
import com.smsapp.student.StudentDtos.AllocateHostelRoomRequest;
import com.smsapp.student.StudentDtos.AssignSectionRequest;
import com.smsapp.student.StudentDtos.AssignTransportRouteRequest;
import com.smsapp.student.StudentDtos.ChangeStudentStatusRequest;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import com.smsapp.transport.TransportService;
import com.smsapp.user.Permissions;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final StudentService studentService;
    private final StudentIdentificationService identificationService;
    private final StudentDocumentService documentService;
    private final TransportService transportService;
    private final HostelService hostelService;

    public StudentController(StudentService studentService, StudentIdentificationService identificationService,
                             StudentDocumentService documentService, TransportService transportService,
                             HostelService hostelService) {
        this.studentService = studentService;
        this.identificationService = identificationService;
        this.documentService = documentService;
        this.transportService = transportService;
        this.hostelService = hostelService;
    }

    /**
     * Only SCHOOL_ADMIN may enroll a student (plan section 2). Kept role-based -- unlike the new sibling/
     * academic-history/identification/document endpoints below, this is a pre-existing, heavily-tested
     * code path; switching it to permission-based checks is a follow-up, not bundled into this phase, to
     * avoid touching every other module's integration-test seed data at once.
     */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<StudentResponse> create(@Valid @RequestBody CreateStudentRequest request,
                                           UriComponentsBuilder uriBuilder) {
        Student student = studentService.create(request);
        URI location = uriBuilder.path("/api/v1/students/{id}").buildAndExpand(student.getId()).toUri();
        return ResponseEntity.created(location).body(StudentResponse.from(student));
    }

    /**
     * Lists/searches students, paginated. {@code q} matches name/admission/roll/enrollment number or
     * parent name/phone; {@code sectionId}/{@code classId}/{@code category}/{@code gender}/{@code status}/
     * {@code rteStatus} are optional exact-match filters (plan Phase 3 section 4).
     */
    @GetMapping
    PagedModel<StudentResponse> list(@RequestParam(required = false) UUID sectionId,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) UUID classId,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String gender,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Boolean rteStatus,
                                     @PageableDefault(size = 20) Pageable pageable) {
        boolean anyFilter = q != null || classId != null || category != null || gender != null
                || status != null || rteStatus != null;
        Page<Student> students;
        if (anyFilter) {
            students = studentService.search(q, classId, sectionId, category, gender, status, rteStatus, pageable);
        } else if (sectionId != null) {
            students = studentService.listBySection(sectionId, pageable);
        } else {
            students = studentService.list(pageable);
        }
        return new PagedModel<>(students.map(StudentResponse::from));
    }

    /**
     * SCHOOL_ADMIN or TEACHER only -- this exposes a student's full record (guardian
     * contact included), so a student or parent must use {@code /api/v1/me/student}
     * or {@code /api/v1/me/children}. Returns 404 (not 403) when the student does
     * not exist -- no existence leak.
     */
    @GetMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    StudentResponse get(@PathVariable UUID id) {
        return StudentResponse.from(studentService.get(id));
    }

    /**
     * Updates a student's editable fields -- the full admission-form breadth (plan Phase 3 section 1).
     * A nonexistent id gets 404, same as {@link #get}. {@code schoolId}/{@code admissionNumber} are not
     * editable (see {@link StudentService#update}).
     */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateStudentRequest request) {
        return StudentResponse.from(studentService.update(id, request));
    }

    /**
     * Deactivate / archive (GRADUATED, LEFT_SCHOOL, TRANSFERRED, INACTIVE) or reactivate a student.
     * No row is ever deleted -- attendance/fee/exam history stays intact (plan Phase 3 section 8).
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStudentStatusRequest request) {
        return StudentResponse.from(studentService.changeStatus(id, request.status()));
    }

    /**
     * Assign / reassign a student to a section. Records a new
     * {@link AcademicHistoryRecord} so the previous placement is never overwritten.
     * 404 if the student or the section does not exist.
     */
    @PatchMapping("/{id}/section")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignSection(@PathVariable UUID id, @Valid @RequestBody AssignSectionRequest request) {
        return StudentResponse.from(studentService.assignSection(id, request.sectionId()));
    }

    /**
     * Assign a student to a transport route, or unassign them with a null
     * {@code routeId}. 404 if the student or the route does not exist.
     */
    @PatchMapping("/{id}/transport-route")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignTransportRoute(@PathVariable UUID id,
                                                @Valid @RequestBody AssignTransportRouteRequest request) {
        return StudentResponse.from(transportService.assignStudentRoute(id, request.routeId()));
    }

    /**
     * Allocate a student to a hostel room, or deallocate them with a null
     * {@code roomId}. 404 if the student or the room does not exist, 400 if the room is already full.
     */
    @PatchMapping("/{id}/hostel-room")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse allocateHostelRoom(@PathVariable UUID id,
                                              @Valid @RequestBody AllocateHostelRoomRequest request) {
        return StudentResponse.from(hostelService.allocateStudentRoom(id, request.roomId()));
    }

    // --- Siblings (plan Phase 3 section 6) -------------------------------

    @GetMapping("/{id}/siblings")
    @PreAuthorize(Permissions.HAS_STUDENT_VIEW)
    List<StudentResponse> siblings(@PathVariable UUID id) {
        return studentService.siblings(id).stream().map(StudentResponse::from).toList();
    }

    @PostMapping("/{id}/siblings")
    @PreAuthorize(Permissions.HAS_STUDENT_EDIT)
    StudentResponse linkSibling(@PathVariable UUID id, @Valid @RequestBody LinkSiblingRequest request) {
        return StudentResponse.from(studentService.linkSibling(id, request.siblingId()));
    }

    // --- Academic history (plan Phase 3 section 7) -----------------------

    @GetMapping("/{id}/academic-history")
    @PreAuthorize(Permissions.HAS_STUDENT_VIEW)
    List<AcademicHistoryEntry> academicHistory(@PathVariable UUID id) {
        return studentService.academicHistory(id).stream().map(AcademicHistoryEntry::from).toList();
    }

    // --- Identification documents (plan Phase 3 section: Identification) ---

    @GetMapping("/{id}/identifications")
    @PreAuthorize(Permissions.HAS_STUDENT_VIEW)
    List<IdentificationEntry> identifications(@PathVariable UUID id) {
        return identificationService.list(id).stream().map(IdentificationEntry::from).toList();
    }

    @PostMapping("/{id}/identifications")
    @PreAuthorize(Permissions.HAS_STUDENT_EDIT)
    ResponseEntity<IdentificationEntry> addIdentification(@PathVariable UUID id,
                                                           @Valid @RequestBody AddIdentificationRequest request) {
        StudentIdentification created = identificationService.add(id, request.idType(), request.idValue(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(IdentificationEntry.from(created));
    }

    @DeleteMapping("/{id}/identifications/{identificationId}")
    @PreAuthorize(Permissions.HAS_STUDENT_EDIT)
    ResponseEntity<Void> removeIdentification(@PathVariable UUID id, @PathVariable UUID identificationId) {
        identificationService.remove(id, identificationId);
        return ResponseEntity.noContent().build();
    }

    // --- Documents (plan Phase 3 section 2) -------------------------------

    @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
    @PreAuthorize(Permissions.HAS_STUDENT_DOCUMENT_UPLOAD)
    ResponseEntity<DocumentEntry> uploadDocument(@PathVariable UUID id,
                                                 @RequestPart("file") MultipartFile file,
                                                 @RequestPart("documentType") String documentType,
                                                 @RequestPart(value = "notes", required = false) String notes,
                                                 Authentication authentication) {
        StudentDocument document = documentService.upload(id, documentType, file, notes, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentEntry.from(document));
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize(Permissions.HAS_STUDENT_DOCUMENT_VIEW)
    List<DocumentEntry> documents(@PathVariable UUID id) {
        return documentService.list(id).stream().map(DocumentEntry::from).toList();
    }

    @GetMapping("/{id}/documents/{documentId}/download")
    @PreAuthorize(Permissions.HAS_STUDENT_DOCUMENT_VIEW)
    ResponseEntity<Resource> downloadDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        StudentDocument metadata = documentService.get(id, documentId);
        Resource file = documentService.load(id, documentId);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(metadata.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(metadata.getOriginalFilename()).build().toString())
                .body(file);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @PreAuthorize(Permissions.HAS_STUDENT_DOCUMENT_DELETE)
    ResponseEntity<Void> deleteDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        documentService.delete(id, documentId);
        return ResponseEntity.noContent().build();
    }

    // --- Photo -------------------------------------------------------

    /** Uploads (replacing any existing) photo. Stored the same way as other documents -- never a public path. */
    @PostMapping(value = "/{id}/photo", consumes = "multipart/form-data")
    @PreAuthorize(Permissions.HAS_STUDENT_EDIT)
    StudentResponse uploadPhoto(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                               Authentication authentication) {
        StudentDocument stored = documentService.upload(id, "PHOTO", file, null, userId(authentication));
        return StudentResponse.from(studentService.setPhotoUrl(id, stored.getId().toString()));
    }

    @GetMapping("/{id}/photo")
    @PreAuthorize(Permissions.HAS_STUDENT_VIEW)
    ResponseEntity<Resource> downloadPhoto(@PathVariable UUID id) {
        Student student = studentService.get(id);
        if (student.getPhotoUrl() == null) {
            return ResponseEntity.notFound().build();
        }
        UUID documentId = UUID.fromString(student.getPhotoUrl());
        StudentDocument metadata = documentService.get(id, documentId);
        Resource file = documentService.load(id, documentId);
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(metadata.getContentType())).body(file);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }

    record LinkSiblingRequest(@NotNull UUID siblingId) {
    }

    record AddIdentificationRequest(@NotBlank String idType, @NotBlank String idValue, String notes) {
    }

    record IdentificationEntry(UUID id, String idType, String idValue, String notes) {
        static IdentificationEntry from(StudentIdentification identification) {
            return new IdentificationEntry(identification.getId(), identification.getIdType(),
                    identification.getIdValue(), identification.getNotes());
        }
    }

    record AcademicHistoryEntry(UUID id, UUID academicYearId, UUID classId, UUID sectionId,
                               java.time.OffsetDateTime recordedAt) {
        static AcademicHistoryEntry from(AcademicHistoryRecord record) {
            return new AcademicHistoryEntry(record.getId(), record.getAcademicYearId(), record.getClassId(),
                    record.getSectionId(), record.getRecordedAt());
        }
    }

    record DocumentEntry(UUID id, String documentType, String originalFilename, String contentType,
                         long fileSizeBytes, UUID uploadedByUserId, String notes, java.time.OffsetDateTime uploadedAt) {
        static DocumentEntry from(StudentDocument document) {
            return new DocumentEntry(document.getId(), document.getDocumentType(), document.getOriginalFilename(),
                    document.getContentType(), document.getFileSizeBytes(), document.getUploadedByUserId(),
                    document.getNotes(), document.getUploadedAt());
        }
    }
}
