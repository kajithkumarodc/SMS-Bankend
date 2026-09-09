package com.smsapp.library;

import com.smsapp.library.LibraryDtos.BookResponse;
import com.smsapp.library.LibraryDtos.CreateBookRequest;
import com.smsapp.library.LibraryDtos.IssueLoanRequest;
import com.smsapp.library.LibraryDtos.LoanHistoryView;
import com.smsapp.library.LibraryDtos.LoanResponse;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Library catalog + issue/return (plan section 2). The catalog is readable by any
 * signed-in role (students and parents may browse it); adding books and issuing /
 * returning loans is SCHOOL_ADMIN only. A student's / parent's own loan history
 * is served ownership-scoped under {@code /api/v1/me/...}.
 */
@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /** Add a book to the catalog. SCHOOL_ADMIN only. */
    @PostMapping("/books")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<BookResponse> addBook(@Valid @RequestBody CreateBookRequest request,
                                                Authentication authentication) {
        LibraryBook created = libraryService.addBook(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BookResponse.from(created));
    }

    /** Browse / search the catalog. Any authenticated role. Optional {@code q} matches title or author. */
    @GetMapping("/books")
    PagedModel<BookResponse> listBooks(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "title", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication authentication) {
        return new PagedModel<>(libraryService.listBooks(tenantId(authentication), q, pageable)
                .map(BookResponse::from));
    }

    /**
     * Issue a book to a student. SCHOOL_ADMIN only. 404 if the book or student is
     * not in the caller's tenant, 400 if no copies are available.
     */
    @PostMapping("/loans")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<LoanResponse> issue(@Valid @RequestBody IssueLoanRequest request,
                                              Authentication authentication) {
        BookLoan loan = libraryService.issue(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanResponse.from(loan));
    }

    /**
     * Mark a loan returned. SCHOOL_ADMIN only. 404 if the loan is not in the
     * caller's tenant, 409 if it has already been returned.
     */
    @PostMapping("/loans/{loanId}/return")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public LoanResponse returnLoan(@PathVariable UUID loanId, Authentication authentication) {
        return LoanResponse.from(libraryService.returnLoan(tenantId(authentication), loanId));
    }

    /**
     * A student's loan history, for staff. SCHOOL_ADMIN or TEACHER only -- a student
     * or parent reads their own via {@code /api/v1/me/...}. 404 if the student is
     * not in the caller's tenant.
     */
    @GetMapping("/loans")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<LoanHistoryView> loanHistory(@RequestParam UUID studentId, Authentication authentication) {
        return libraryService.loanHistoryForStudent(tenantId(authentication), studentId).stream()
                .map(LoanHistoryView::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
