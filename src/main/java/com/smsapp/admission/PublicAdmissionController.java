package com.smsapp.admission;

import com.smsapp.admission.AdmissionDtos.PublicOpenCycleResponse;
import com.smsapp.admission.AdmissionDtos.StatusLookupRequest;
import com.smsapp.admission.AdmissionDtos.StatusLookupResponse;
import com.smsapp.admission.AdmissionDtos.SubmitApplicationRequest;
import com.smsapp.admission.AdmissionDtos.SubmitApplicationResponse;
import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.common.ApiException;
import com.smsapp.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Everything a public, unauthenticated applicant can reach (plan Phase 4.5 parts 2/4/5/6/15) --
 * every endpoint here is listed in {@code SecurityConfig}'s {@code permitAll()}. No endpoint here
 * ever returns an internal id, a reviewer's identity, another applicant's data, or a document file
 * outside its own application. Submission and document upload are rate-limited per client IP
 * (self-hosted in-memory limiter, no external service -- plan part 27).
 */
@RestController
@RequestMapping("/api/v1/public/admissions")
public class PublicAdmissionController {

    private final AdmissionCycleService cycleService;
    private final AdmissionApplicationService applicationService;
    private final AdmissionApplicationDocumentService documentService;
    private final com.smsapp.school.SchoolRepository schoolRepository;
    private final ClassRepository classRepository;
    private final RateLimiter rateLimiter;
    private final int maxRequests;
    private final int windowMinutes;

    public PublicAdmissionController(AdmissionCycleService cycleService, AdmissionApplicationService applicationService,
                                      AdmissionApplicationDocumentService documentService,
                                      com.smsapp.school.SchoolRepository schoolRepository,
                                      ClassRepository classRepository, RateLimiter rateLimiter,
                                      @Value("${app.rate-limit.admission-submit.max-requests}") int maxRequests,
                                      @Value("${app.rate-limit.admission-submit.window-minutes}") int windowMinutes) {
        this.cycleService = cycleService;
        this.applicationService = applicationService;
        this.documentService = documentService;
        this.schoolRepository = schoolRepository;
        this.classRepository = classRepository;
        this.rateLimiter = rateLimiter;
        this.maxRequests = maxRequests;
        this.windowMinutes = windowMinutes;
    }

    /** Minimal, safe school picker for the public form -- id and name only. */
    @GetMapping("/schools")
    List<SchoolOption> schools() {
        return schoolRepository.findAllByOrderByName().stream()
                .map(s -> new SchoolOption(s.getId(), s.getName())).toList();
    }

    record SchoolOption(UUID id, String name) {
    }

    /** Minimal, safe class/grade picker for the "applying for" field -- id and name only, never enrollment data. */
    @GetMapping("/schools/{schoolId}/classes")
    List<ClassOption> classes(@PathVariable UUID schoolId) {
        return classRepository.findAllByOrderByName().stream()
                .filter(c -> schoolId.equals(c.getSchoolId()))
                .map(c -> new ClassOption(c.getId(), c.getName())).toList();
    }

    record ClassOption(UUID id, String name) {
    }

    @GetMapping("/schools/{schoolId}/open-cycle")
    PublicOpenCycleResponse openCycle(@PathVariable UUID schoolId) {
        return cycleService.currentOpenCycle(schoolId);
    }

    @PostMapping("/applications")
    ResponseEntity<SubmitApplicationResponse> submit(@Valid @RequestBody SubmitApplicationRequest request,
                                                       HttpServletRequest httpRequest) {
        enforceRateLimit(httpRequest);
        AdmissionApplication saved = applicationService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubmitApplicationResponse(
                saved.getApplicationNumber(), "Application submitted successfully."));
    }

    /**
     * Uploading a document requires the same reference+email proof as the status lookup (plan part
     * 6) -- an applicant can only add documents to their own, not-yet-decided application.
     */
    @PostMapping(value = "/applications/{applicationNumber}/documents", consumes = "multipart/form-data")
    ResponseEntity<Void> uploadDocument(@PathVariable String applicationNumber,
                                        @RequestPart("email") String email,
                                        @RequestPart("documentType") String documentType,
                                        @RequestPart("file") MultipartFile file,
                                        HttpServletRequest httpRequest) {
        enforceRateLimit(httpRequest);
        AdmissionApplication application = applicationService.verifyPublicAccess(applicationNumber, email);
        documentService.upload(application.getId(), documentType, file, null);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/status")
    StatusLookupResponse status(@Valid @RequestBody StatusLookupRequest request) {
        return applicationService.statusLookup(request.applicationNumber(), request.email());
    }

    private void enforceRateLimit(HttpServletRequest request) {
        if (!rateLimiter.allow(clientIp(request), maxRequests, windowMinutes)) {
            throw new ApiException("Too many requests -- please try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /** Best-effort caller IP: the first hop of X-Forwarded-For behind a reverse proxy, else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
