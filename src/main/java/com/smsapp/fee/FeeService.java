package com.smsapp.fee;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fee structures + invoices + Razorpay checkout. We never store raw payment
 * data -- only Razorpay's own order/payment reference ids (plan section 7.2a).
 */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    /** Razorpay charges in the smallest currency unit; rupees -> paise. */
    private static final BigDecimal MINOR_UNIT_FACTOR = BigDecimal.valueOf(100);

    static final String NOTE_INVOICE_ID = "invoiceId";

    private final FeeStructureRepository feeStructureRepository;
    private final InvoiceRepository invoiceRepository;
    private final SchoolRepository schoolRepository;
    private final StudentRepository studentRepository;
    private final RazorpayGateway razorpayGateway;
    private final RazorpayProperties razorpayProperties;
    private final AuditService auditService;

    public FeeService(FeeStructureRepository feeStructureRepository, InvoiceRepository invoiceRepository,
                      SchoolRepository schoolRepository, StudentRepository studentRepository,
                      RazorpayGateway razorpayGateway, RazorpayProperties razorpayProperties,
                      AuditService auditService) {
        this.feeStructureRepository = feeStructureRepository;
        this.invoiceRepository = invoiceRepository;
        this.schoolRepository = schoolRepository;
        this.studentRepository = studentRepository;
        this.razorpayGateway = razorpayGateway;
        this.razorpayProperties = razorpayProperties;
        this.auditService = auditService;
    }

    // --- Fee structures ------------------------------------------------

    /**
     * @throws ApiException 404 if the school does not exist.
     */
    @Transactional
    public FeeStructure createFeeStructure(CreateFeeStructureRequest request) {
        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        FeeStructure structure = new FeeStructure();
        structure.setSchoolId(request.schoolId());
        structure.setName(request.name().trim());
        structure.setAmount(request.amount());
        structure.setDueDate(request.dueDate());
        FeeStructure saved = feeStructureRepository.save(structure);

        auditService.log(AuditActions.FEE_STRUCTURE_CREATED, AuditActions.FEE_STRUCTURE, saved.getId(),
                Map.of("name", saved.getName(), "amount", saved.getAmount().toPlainString(),
                        "schoolId", saved.getSchoolId().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<FeeStructure> listFeeStructures() {
        return feeStructureRepository.findAllByOrderByCreatedAtDesc();
    }

    // --- Invoices ----------------------------------------------------

    /**
     * Generate a PENDING invoice for one student against one fee structure. The
     * amount is copied from the structure, not taken from the request.
     *
     * @throws ApiException 404 if the student or the fee structure does not exist.
     */
    @Transactional
    public Invoice createInvoice(CreateInvoiceRequest request) {
        if (studentRepository.findById(request.studentId()).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        FeeStructure structure = feeStructureRepository.findById(request.feeStructureId())
                .orElseThrow(() -> new ApiException("Fee structure not found", HttpStatus.NOT_FOUND));

        Invoice invoice = new Invoice();
        invoice.setStudentId(request.studentId());
        invoice.setFeeStructureId(structure.getId());
        invoice.setAmount(structure.getAmount());
        invoice.setStatus(InvoiceStatus.PENDING);
        Invoice saved = invoiceRepository.save(invoice);

        auditService.log(AuditActions.INVOICE_CREATED, AuditActions.INVOICE, saved.getId(),
                Map.of("studentId", saved.getStudentId().toString(),
                        "feeStructureId", saved.getFeeStructureId().toString(),
                        "amount", saved.getAmount().toPlainString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional(readOnly = true)
    public List<Invoice> listInvoicesForStudent(UUID studentId) {
        if (studentRepository.findById(studentId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        return invoiceRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    /** Portal use: the caller's ownership of {@code studentId} has already been proven. */
    @Transactional(readOnly = true)
    public List<Invoice> invoicesForOwnedStudent(UUID studentId) {
        return invoiceRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    // --- Checkout --------------------------------------------------

    /**
     * When the caller is a PARENT rather than staff ({@code parentGuardianUserId}
     * non-null), the invoice must belong to one of that parent's own children. A
     * mismatch is reported as 404, never 403, so it never leaks that another
     * family's invoice exists (same rule as the portal ownership checks).
     */
    private Invoice loadInvoiceForActor(UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        if (parentGuardianUserId != null
                && studentRepository.findByIdAndGuardianUserId(invoice.getStudentId(), parentGuardianUserId)
                        .isEmpty()) {
            throw new ApiException("Invoice not found", HttpStatus.NOT_FOUND);
        }
        return invoice;
    }

    /**
     * Create a Razorpay Order for an invoice and hand the browser only what its
     * Checkout widget needs -- order id, public key id, amount. No raw payment
     * data ever crosses this boundary (plan section 7.2a).
     *
     * @param parentGuardianUserId the caller's user id when the caller is a PARENT
     *        (restricts the invoice to their own children); {@code null} for staff.
     * @throws ApiException 404 if the invoice does not exist (or is not the
     *         parent's child's), 409 if it is already paid.
     */
    @Transactional
    public CheckoutResponse startCheckout(UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = loadInvoiceForActor(invoiceId, parentGuardianUserId);
        if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
            throw new ApiException("This invoice is already paid", HttpStatus.CONFLICT);
        }

        long amountInPaise = invoice.getAmount().multiply(MINOR_UNIT_FACTOR).longValueExact();
        String currency = razorpayProperties.currency();
        String orderId = razorpayGateway.createOrder(amountInPaise, currency, invoice.getId().toString(),
                Map.of(NOTE_INVOICE_ID, invoice.getId().toString()));

        invoice.setRazorpayOrderId(orderId);
        invoiceRepository.save(invoice);

        auditService.log(AuditActions.INVOICE_CHECKOUT_STARTED, AuditActions.INVOICE, invoice.getId(),
                Map.of("razorpayOrderId", orderId, "amountInPaise", amountInPaise));

        return new CheckoutResponse(invoice.getId(), orderId, razorpayGateway.keyId(), amountInPaise, currency);
    }

    // --- Payment webhook ------------------------------------------

    /**
     * Applied only after the webhook signature has been verified by the caller.
     *
     * <p>Idempotent: a duplicate delivery for an already-PAID invoice is a no-op,
     * so a Razorpay retry can never double-apply (plan section 7.2a).
     */
    @Transactional
    public void markInvoicePaid(UUID invoiceId, String razorpayOrderId, String razorpayPaymentId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .or(() -> invoiceRepository.findByRazorpayOrderId(razorpayOrderId))
                .orElse(null);
        if (invoice == null) {
            log.warn("Razorpay webhook for unknown invoice invoiceId={} orderId={}", invoiceId, razorpayOrderId);
            return;
        }
        if (invoice.getRazorpayOrderId() != null && !invoice.getRazorpayOrderId().equals(razorpayOrderId)) {
            log.warn("Razorpay webhook order id {} does not match invoice {} order id {}",
                    razorpayOrderId, invoice.getId(), invoice.getRazorpayOrderId());
            return;
        }
        if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
            return;
        }

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setRazorpayOrderId(razorpayOrderId);
        invoice.setRazorpayPaymentId(razorpayPaymentId);
        invoice.setPaidAt(OffsetDateTime.now());
        invoiceRepository.save(invoice);

        auditService.logAs(null, AuditActions.INVOICE_PAID, AuditActions.INVOICE, invoice.getId(),
                Map.of("razorpayOrderId", razorpayOrderId, "razorpayPaymentId", razorpayPaymentId));
    }

    // --- DEV-ONLY payment simulation ------------------------------

    /** Payment/order reference stamped on invoices flipped to PAID by the dev-tools endpoint. */
    static final String SIMULATED_PAYMENT_REF = "dev-simulated";

    /**
     * DEV-ONLY. Flips an invoice to PAID exactly as {@link #markInvoicePaid} would,
     * but on the authority of an authenticated SCHOOL_ADMIN instead of a
     * signature-verified Razorpay webhook. This exists purely so a local demo can
     * show the "paid" state without a public webhook tunnel.
     *
     * <p><b>Never a substitute for the real webhook.</b> It is only reachable when
     * {@code app.dev-tools-enabled=true} (see {@link DevToolsController}); in any
     * deployed environment that flag is false and this path does not exist.
     *
     * <p>Idempotent: a second call on an already-paid invoice is a no-op.
     *
     * @param parentGuardianUserId the caller's user id when the caller is a PARENT
     *        (restricts the invoice to their own children); {@code null} for staff.
     * @throws ApiException 404 if the invoice does not exist (or is not the
     *         parent's child's).
     */
    @Transactional
    public Invoice simulatePaymentSuccess(UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = loadInvoiceForActor(invoiceId, parentGuardianUserId);
        if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
            return invoice;
        }

        invoice.setStatus(InvoiceStatus.PAID);
        if (invoice.getRazorpayOrderId() == null) {
            invoice.setRazorpayOrderId(SIMULATED_PAYMENT_REF);
        }
        invoice.setRazorpayPaymentId(SIMULATED_PAYMENT_REF);
        invoice.setPaidAt(OffsetDateTime.now());
        Invoice saved = invoiceRepository.save(invoice);

        log.warn("DEV-TOOLS: invoice {} marked PAID via simulate-payment-success (not a real Razorpay webhook)",
                saved.getId());
        auditService.log(AuditActions.INVOICE_PAID_SIMULATED, AuditActions.INVOICE, saved.getId(),
                Map.of("note", "dev-tools simulate-payment-success; not a verified webhook"));
        return saved;
    }
}
