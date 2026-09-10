package com.smsapp.portal;

import com.smsapp.portal.PortalDtos.AttendanceEntryView;
import com.smsapp.portal.PortalDtos.ExamResultView;
import com.smsapp.portal.PortalDtos.InvoiceView;
import com.smsapp.portal.PortalDtos.LoanView;
import com.smsapp.portal.PortalDtos.StudentView;
import com.smsapp.portal.PortalDtos.TransportView;
import com.smsapp.user.Roles;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Student and parent self-service views under {@code /api/v1/me}. Ownership is
 * enforced in {@link PortalService} by the queries themselves; a mismatched id
 * returns 404, never 403.
 */
@RestController
@RequestMapping("/api/v1/me")
public class PortalController {

    private final PortalService portalService;

    public PortalController(PortalService portalService) {
        this.portalService = portalService;
    }

    /** STUDENT: their own student record. 404 if no record is linked yet. */
    @GetMapping("/student")
    @PreAuthorize(Roles.HAS_STUDENT)
    public StudentView ownStudent(Authentication authentication) {
        return StudentView.from(portalService.ownStudent(tenantId(authentication), userId(authentication)));
    }

    /** STUDENT: their own attendance history only. */
    @GetMapping("/student/attendance")
    @PreAuthorize(Roles.HAS_STUDENT)
    public PagedModel<AttendanceEntryView> ownAttendance(@PageableDefault(size = 50) Pageable pageable,
                                                         Authentication authentication) {
        return new PagedModel<>(portalService
                .ownAttendance(tenantId(authentication), userId(authentication), pageable)
                .map(AttendanceEntryView::from));
    }

    /** PARENT: every student linked to this account (supports multiple children; may be empty). */
    @GetMapping("/children")
    @PreAuthorize(Roles.HAS_PARENT)
    public List<StudentView> children(Authentication authentication) {
        return portalService.children(tenantId(authentication), userId(authentication))
                .stream().map(StudentView::from).toList();
    }

    /** PARENT: one of their own children's attendance. 404 if the student is not this parent's child. */
    @GetMapping("/children/{studentId}/attendance")
    @PreAuthorize(Roles.HAS_PARENT)
    public PagedModel<AttendanceEntryView> childAttendance(@PathVariable UUID studentId,
                                                           @PageableDefault(size = 50) Pageable pageable,
                                                           Authentication authentication) {
        return new PagedModel<>(portalService
                .childAttendance(tenantId(authentication), userId(authentication), studentId, pageable)
                .map(AttendanceEntryView::from));
    }

    /** STUDENT: their own exam results only. 404 if no record is linked yet. */
    @GetMapping("/student/results")
    @PreAuthorize(Roles.HAS_STUDENT)
    public List<ExamResultView> ownResults(Authentication authentication) {
        return portalService.ownResults(tenantId(authentication), userId(authentication))
                .stream().map(ExamResultView::from).toList();
    }

    /** PARENT: one of their own children's exam results. 404 if the student is not this parent's child. */
    @GetMapping("/children/{studentId}/results")
    @PreAuthorize(Roles.HAS_PARENT)
    public List<ExamResultView> childResults(@PathVariable UUID studentId, Authentication authentication) {
        return portalService.childResults(tenantId(authentication), userId(authentication), studentId)
                .stream().map(ExamResultView::from).toList();
    }

    /** PARENT: one of their own children's invoices (fee dues). 404 if the student is not this parent's child. */
    @GetMapping("/children/{studentId}/invoices")
    @PreAuthorize(Roles.HAS_PARENT)
    public List<InvoiceView> childInvoices(@PathVariable UUID studentId, Authentication authentication) {
        return portalService.childInvoices(tenantId(authentication), userId(authentication), studentId)
                .stream().map(InvoiceView::from).toList();
    }

    /** STUDENT: their own library loan history only. 404 if no record is linked yet. */
    @GetMapping("/student/library")
    @PreAuthorize(Roles.HAS_STUDENT)
    public List<LoanView> ownLibrary(Authentication authentication) {
        return portalService.ownLibrary(tenantId(authentication), userId(authentication))
                .stream().map(LoanView::from).toList();
    }

    /** PARENT: one of their own children's library loan history. 404 if the student is not this parent's child. */
    @GetMapping("/children/{studentId}/library")
    @PreAuthorize(Roles.HAS_PARENT)
    public List<LoanView> childLibrary(@PathVariable UUID studentId, Authentication authentication) {
        return portalService.childLibrary(tenantId(authentication), userId(authentication), studentId)
                .stream().map(LoanView::from).toList();
    }

    /** STUDENT: their own transport assignment (route + vehicle + driver info). 404 if none is assigned. */
    @GetMapping("/student/transport")
    @PreAuthorize(Roles.HAS_STUDENT)
    public TransportView ownTransport(Authentication authentication) {
        return TransportView.from(portalService.ownTransport(tenantId(authentication), userId(authentication)));
    }

    /** PARENT: one of their own children's transport assignment. 404 if not their child or none is assigned. */
    @GetMapping("/children/{studentId}/transport")
    @PreAuthorize(Roles.HAS_PARENT)
    public TransportView childTransport(@PathVariable UUID studentId, Authentication authentication) {
        return TransportView.from(
                portalService.childTransport(tenantId(authentication), userId(authentication), studentId));
    }

    private static UUID tenantId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getClaimAsString("tenant_id"));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getSubject());
    }

    private static Jwt jwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
