package com.smsapp.expense;

import com.smsapp.common.ApiException;
import com.smsapp.expense.ExpenseDtos.ExpenseRequest;
import com.smsapp.expense.ExpenseDtos.ExpenseResponse;
import com.smsapp.expense.ExpenseDtos.ExpenseTotalResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Expenses, gated by the EXPENSE_* permissions seeded in V43. */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    /** Sortable list columns: API sort key -> entity property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "description", "description",
            "invoiceNumber", "invoiceNumber",
            "expenseDate", "expenseDate",
            "expenseHeadName", "expenseHead.name",
            "amount", "amount");

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_EXPENSE_CREATE)
    ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.toResponse(service.create(request, userId)));
    }

    /**
     * Search/list, paginated. {@code q} and {@code filter} (both optional, both must match) match name, description,
     * invoice number or expense head; {@code from}/{@code to} bound the expense date. Sort keys are the
     * {@link #SORTABLE} names (400 otherwise); by default the newest expense date is first.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_EXPENSE_VIEW)
    PagedModel<ExpenseResponse> list(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String filter,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @PageableDefault(size = 50) Pageable pageable) {
        requireValidRange(from, to);
        Page<Expense> page = service.search(q, filter, from, to, withEntitySort(pageable));
        return new PagedModel<>(new PageImpl<>(service.toResponses(page.getContent()), pageable, page.getTotalElements()));
    }

    /** Grand total of every expense the same filters match, across all pages. */
    @GetMapping("/total")
    @PreAuthorize(Permissions.HAS_EXPENSE_VIEW)
    ExpenseTotalResponse total(@RequestParam(required = false) String q,
                               @RequestParam(required = false) String filter,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireValidRange(from, to);
        return new ExpenseTotalResponse(service.total(q, filter, from, to));
    }

    private static void requireValidRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException("from must not be after to", HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_EXPENSE_VIEW)
    ExpenseResponse get(@PathVariable UUID id) {
        return service.toResponse(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_EXPENSE_EDIT)
    ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        return service.toResponse(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_EXPENSE_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Attach or replace the document. Allowed for whoever can add an expense (it's part of the Add form) or edit one. */
    @PutMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_EXPENSE_CREATE + " or " + Permissions.HAS_EXPENSE_EDIT)
    ExpenseResponse attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return service.toResponse(service.attach(id, file));
    }

    @DeleteMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_EXPENSE_EDIT)
    ExpenseResponse removeAttachment(@PathVariable UUID id) {
        return service.toResponse(service.removeAttachment(id));
    }

    @GetMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_EXPENSE_VIEW)
    ResponseEntity<Resource> downloadAttachment(@PathVariable UUID id) {
        Expense expense = service.get(id);
        Resource file = service.loadAttachment(expense);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(expense.getAttachmentContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(expense.getAttachmentOriginalFilename()).build().toString())
                .body(file);
    }

    private static Pageable withEntitySort(Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORTABLE.get(order.getProperty());
            if (property == null) {
                throw new ApiException("Can't sort expenses by '" + order.getProperty() + "'", HttpStatus.BAD_REQUEST);
            }
            sort = sort.and(Sort.by(order.withProperty(property)));
        }
        if (sort.getOrderFor("expenseDate") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "expenseDate"));
        }
        // Stable order for rows on the same date: newest entry first.
        sort = sort.and(Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
