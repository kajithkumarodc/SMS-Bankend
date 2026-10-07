package com.smsapp.fee;

import com.smsapp.fee.FeeLineService.CollectionOutcome;
import com.smsapp.fee.FeeLineService.LinePaymentRequest;
import com.smsapp.fee.StudentFeesService.CollectionReceipt;
import com.smsapp.fee.StudentFeesService.StudentFeesView;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Fees Collection -> Collect Fees -> Student Fees: the line-by-line view, collecting, and collection receipts. */
@RestController
@RequestMapping("/api/v1")
public class StudentFeesController {

    private final StudentFeesService studentFeesService;
    private final FeeLineService lineService;

    public StudentFeesController(StudentFeesService studentFeesService, FeeLineService lineService) {
        this.studentFeesService = studentFeesService;
        this.lineService = lineService;
    }

    @GetMapping("/students/{studentId}/fees")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    StudentFeesView fees(@PathVariable UUID studentId) {
        return studentFeesService.view(studentId);
    }

    record CollectLine(@NotNull UUID invoiceLineId, BigDecimal amount, BigDecimal fine) {
    }

    record CollectRequest(
            @NotNull LocalDate paymentDate,
            @NotBlank String method,
            @Size(max = 255) String referenceNumber,
            @Size(max = 500) String notes,
            @NotEmpty @Size(max = 200) List<@Valid CollectLine> lines) {
    }

    record CollectResponse(UUID collectionId, List<String> receiptNumbers, BigDecimal total) {
    }

    /** Collect fees for the chosen lines. 400 on bad input, 404 if a line isn't this student's, 409 if already paid. */
    @PostMapping("/students/{studentId}/fee-collections")
    @PreAuthorize(Permissions.HAS_FEE_COLLECT)
    ResponseEntity<CollectResponse> collect(@PathVariable UUID studentId, @Valid @RequestBody CollectRequest request,
                                            Authentication authentication) {
        CollectionOutcome outcome = lineService.collect(studentId, request.paymentDate(), request.method(),
                request.referenceNumber(), request.notes(),
                request.lines().stream().map(l -> new LinePaymentRequest(l.invoiceLineId(), l.amount(), l.fine())).toList(),
                UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CollectResponse(outcome.collectionId(),
                outcome.payments().stream().map(FeePayment::getReceiptNumber).toList(), outcome.total()));
    }

    @GetMapping("/fee-collections/{collectionId}")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    CollectionReceipt receipt(@PathVariable UUID collectionId) {
        return studentFeesService.receipt(collectionId);
    }
}
