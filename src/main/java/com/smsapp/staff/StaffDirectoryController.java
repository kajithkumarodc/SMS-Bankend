package com.smsapp.staff;

import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffDirectoryDtos.CreatedStaffResponse;
import com.smsapp.staff.StaffDirectoryDtos.ImportResult;
import com.smsapp.staff.StaffDirectoryDtos.StaffCardResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffMemberResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffOptionsResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffRequest;
import com.smsapp.staff.StaffDirectoryService.CreatedStaff;
import com.smsapp.user.Permissions;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Human Resource > Staff Directory, gated by the STAFF_* permissions (seeded in V22): STAFF_VIEW to read,
 * STAFF_CREATE to add or import, STAFF_EDIT to change a record, its photo and its documents.
 */
@RestController
@RequestMapping("/api/v1/staff-members")
public class StaffDirectoryController {

    private final StaffDirectoryService service;
    private final StaffImportService importService;

    public StaffDirectoryController(StaffDirectoryService service, StaffImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    /** Role, designation and department choices for the criteria, Add Staff and Import Staff pages. */
    @GetMapping("/options")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW)
    StaffOptionsResponse options(Authentication authentication) {
        return service.options(isSuperAdmin(authentication));
    }

    /** The directory: optional {@code roleId}, keyword {@code q} and {@code status} (default ACTIVE). */
    @GetMapping
    @PreAuthorize(Permissions.HAS_STAFF_VIEW)
    List<StaffCardResponse> list(@RequestParam(required = false) UUID roleId, @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String status) {
        return service.list(roleId, q, status);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW)
    StaffMemberResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Adds the staff member and their login; the response carries the login's temporary password, once. */
    @PostMapping
    @PreAuthorize(Permissions.HAS_STAFF_CREATE)
    ResponseEntity<CreatedStaffResponse> create(@Valid @RequestBody StaffRequest request, Authentication authentication) {
        CreatedStaff created = service.create(request, isSuperAdmin(authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedStaffResponse(service.toResponse(created.profile()), created.temporaryPassword()));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_STAFF_EDIT)
    StaffMemberResponse update(@PathVariable UUID id, @Valid @RequestBody StaffRequest request,
                               Authentication authentication) {
        return service.toResponse(service.update(id, request, isSuperAdmin(authentication)));
    }

    /** Imports staff from a CSV file; every row gets the chosen role (and designation/department, if given). */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_STAFF_CREATE)
    ImportResult importCsv(@RequestPart("file") MultipartFile file, @RequestParam UUID roleId,
                           @RequestParam(required = false) UUID designationId,
                           @RequestParam(required = false) UUID departmentId, Authentication authentication) throws IOException {
        return importService.importCsv(file.getBytes(), roleId, designationId, departmentId, isSuperAdmin(authentication));
    }

    /** The sample file for the import page. */
    @GetMapping("/import/sample")
    @PreAuthorize(Permissions.HAS_STAFF_CREATE)
    ResponseEntity<String> sampleCsv() {
        String header = String.join(",", StaffImportService.COLUMNS);
        java.util.Map<String, String> sample = java.util.Map.ofEntries(
                java.util.Map.entry("employee_id", "1001"), java.util.Map.entry("qualification", "B.Ed."),
                java.util.Map.entry("work_exp", "3 Yrs"), java.util.Map.entry("name", "Jason"),
                java.util.Map.entry("surname", "Sharlton"), java.util.Map.entry("father_name", "Max Sharlton"),
                java.util.Map.entry("mother_name", "Arya Sharlton"), java.util.Map.entry("contact_no", "4654665456"),
                java.util.Map.entry("emergency_contact_no", "5456121565"),
                java.util.Map.entry("email", "jason@example.com"), java.util.Map.entry("dob", "1980-06-16"),
                java.util.Map.entry("marital_status", "Married"), java.util.Map.entry("date_of_joining", "2021-06-24"),
                java.util.Map.entry("local_address", "83 Evan Street Brooklyn"), java.util.Map.entry("gender", "Male"),
                java.util.Map.entry("basic_salary", "25000"), java.util.Map.entry("contract_type", "Permanent"));
        String example = StaffImportService.COLUMNS.stream().map(column -> sample.getOrDefault(column, ""))
                .collect(java.util.stream.Collectors.joining(","));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("staff_csvfile.csv").build().toString())
                .body(header + "\n" + example + "\n");
    }

    // --- Photo ---------------------------------------------------------------------------------

    @PutMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_STAFF_CREATE + " or " + Permissions.HAS_STAFF_EDIT)
    StaffMemberResponse attachPhoto(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return service.toResponse(service.attachPhoto(id, file));
    }

    @DeleteMapping("/{id}/photo")
    @PreAuthorize(Permissions.HAS_STAFF_EDIT)
    StaffMemberResponse removePhoto(@PathVariable UUID id) {
        return service.toResponse(service.removePhoto(id));
    }

    /** The photo, shown inline (cards and the profile use it as an image). */
    @GetMapping("/{id}/photo")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW)
    ResponseEntity<Resource> photo(@PathVariable UUID id) {
        StaffProfile profile = service.require(id);
        Resource file = service.loadPhoto(profile);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(profile.getPhotoContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(file);
    }

    // --- Documents -------------------------------------------------------------------------------

    @PutMapping(value = "/{id}/documents/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_STAFF_CREATE + " or " + Permissions.HAS_STAFF_EDIT)
    StaffMemberResponse attachDocument(@PathVariable UUID id, @PathVariable String kind,
                                       @RequestPart("file") MultipartFile file) {
        return service.toResponse(service.attachDocument(id, kind, file));
    }

    @DeleteMapping("/{id}/documents/{kind}")
    @PreAuthorize(Permissions.HAS_STAFF_EDIT)
    StaffMemberResponse removeDocument(@PathVariable UUID id, @PathVariable String kind) {
        return service.toResponse(service.removeDocument(id, kind));
    }

    @GetMapping("/{id}/documents/{kind}")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW)
    ResponseEntity<Resource> downloadDocument(@PathVariable UUID id, @PathVariable String kind) {
        StaffDocument document = service.document(id, kind);
        Resource file = service.loadDocument(document);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.getOriginalFilename()).build().toString())
                .body(file);
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Roles.SUPER_ADMIN).equals(a.getAuthority()));
    }
}
