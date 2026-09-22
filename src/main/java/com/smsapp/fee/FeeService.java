package com.smsapp.fee;

import com.smsapp.academics.ClassRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.fee.FeeDtos.FeeStructureItemResponse;
import com.smsapp.fee.FeeDtos.LineItemRequest;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fee structures + invoices + Razorpay checkout. We never store raw payment
 * data -- only Razorpay's own order/payment reference ids (plan section 7.2a).
 * Every payment -- this class's Razorpay webhook/dev-simulate path included --
 * is applied through {@link FeePaymentRecorder}, the one place a
 * {@link FeePayment} ledger row is written and {@code Invoice.paidAmount} is
 * mutated, so manual collection ({@link FeeCollectionService}) and online
 * payment can never disagree about how much has actually been collected
 * (plan Phase 5 part H).
 */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    /** Razorpay charges in the smallest currency unit; rupees -> paise. */
    private static final BigDecimal MINOR_UNIT_FACTOR = BigDecimal.valueOf(100);

    static final String NOTE_INVOICE_ID = "invoiceId";

    private final FeeStructureRepository feeStructureRepository;
    private final FeeStructureItemRepository feeStructureItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final SchoolRepository schoolRepository;
    private final ClassRepository classRepository;
    private final StudentRepository studentRepository;
    private final FeeTypeRepository feeTypeRepository;
    private final FeePaymentRepository feePaymentRepository;
    private final RazorpayGateway razorpayGateway;
    private final RazorpayProperties razorpayProperties;
    private final FeePaymentRecorder paymentRecorder;
    private final AuditService auditService;

    public FeeService(FeeStructureRepository feeStructureRepository,
                      FeeStructureItemRepository feeStructureItemRepository, InvoiceRepository invoiceRepository,
                      SchoolRepository schoolRepository, ClassRepository classRepository,
                      StudentRepository studentRepository, FeeTypeRepository feeTypeRepository,
                      FeePaymentRepository feePaymentRepository, RazorpayGateway razorpayGateway,
                      RazorpayProperties razorpayProperties, FeePaymentRecorder paymentRecorder,
                      AuditService auditService) {
        this.feeStructureRepository = feeStructureRepository;
        this.feeStructureItemRepository = feeStructureItemRepository;
        this.invoiceRepository = invoiceRepository;
        this.schoolRepository = schoolRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.feeTypeRepository = feeTypeRepository;
        this.feePaymentRepository = feePaymentRepository;
        this.razorpayGateway = razorpayGateway;
        this.razorpayProperties = razorpayProperties;
        this.paymentRecorder = paymentRecorder;
        this.auditService = auditService;
    }

    // --- Fee structures ------------------------------------------------

    /**
     * Either {@code request.items()} or {@code request.amount()} must be given (see
     * {@link CreateFeeStructureRequest}); when items are given, the structure's
     * {@code amount} is computed as their sum and persisted alongside them.
     *
     * @throws ApiException 404 if the school (or the class, when given) does not
     *         exist, 400 if neither items nor amount is given, or an item's
     *         category is not recognized.
     */
    @Transactional
    public FeeStructure createFeeStructure(CreateFeeStructureRequest request) {
        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (request.classId() != null && !classRepository.existsById(request.classId())) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        String frequency = request.frequency() == null || request.frequency().isBlank()
                ? FeeFrequency.ONE_TIME : request.frequency();
        if (!FeeFrequency.isValid(frequency)) {
            throw new ApiException("Not a recognized frequency: " + frequency, HttpStatus.BAD_REQUEST);
        }

        boolean hasItems = request.items() != null && !request.items().isEmpty();
        BigDecimal totalAmount;
        if (hasItems) {
            totalAmount = BigDecimal.ZERO;
            for (LineItemRequest item : request.items()) {
                if (FeeStructureItemCategory.normalizeOrNull(item.category()) == null) {
                    throw new ApiException(
                            "Not a recognized fee category: " + item.category(), HttpStatus.BAD_REQUEST);
                }
                if (item.feeTypeId() != null && !feeTypeRepository.existsById(item.feeTypeId())) {
                    throw new ApiException("Fee type not found", HttpStatus.NOT_FOUND);
                }
                totalAmount = totalAmount.add(item.amount());
            }
        } else {
            if (request.amount() == null) {
                throw new ApiException("Either amount or items must be provided", HttpStatus.BAD_REQUEST);
            }
            totalAmount = request.amount();
        }

        FeeStructure structure = new FeeStructure();
        structure.setSchoolId(request.schoolId());
        structure.setClassId(request.classId());
        structure.setAcademicYear(request.academicYear().trim());
        structure.setName(request.name().trim());
        structure.setAmount(totalAmount);
        structure.setDueDate(request.dueDate());
        structure.setFrequency(frequency);
        structure.setLateFeeAmount(request.lateFeeAmount());
        structure.setStatus(FeeStructureStatus.ACTIVE);
        FeeStructure saved = feeStructureRepository.save(structure);

        if (hasItems) {
            int sequence = 0;
            List<FeeStructureItem> items = new ArrayList<>();
            for (LineItemRequest item : request.items()) {
                FeeStructureItem entity = new FeeStructureItem();
                entity.setFeeStructureId(saved.getId());
                entity.setCategory(FeeStructureItemCategory.normalizeOrNull(item.category()));
                entity.setLabel(blankToNull(item.label()));
                entity.setFeeTypeId(item.feeTypeId());
                entity.setAmount(item.amount());
                entity.setSequenceOrder(sequence++);
                items.add(entity);
            }
            feeStructureItemRepository.saveAll(items);
        }

        auditService.log(AuditActions.FEE_STRUCTURE_CREATED, AuditActions.FEE_STRUCTURE, saved.getId(),
                Map.of("name", saved.getName(), "amount", saved.getAmount().toPlainString(),
                        "schoolId", saved.getSchoolId().toString(),
                        "itemCount", hasItems ? request.items().size() : 0));
        return saved;
    }

    /** Optional class + academic-year filter; either or both may be {@code null}. */
    @Transactional(readOnly = true)
    public List<FeeStructure> listFeeStructures(UUID classId, String academicYear) {
        return feeStructureRepository.search(classId, academicYear);
    }

    /**
     * A structure's line items, in display order, with each item's optional {@link FeeType} name
     * resolved. A structure with no persisted items (the backward-compatible flat-amount case) is
     * presented as a single implicit item covering the whole amount, so callers always see a
     * breakdown that sums to the total rather than special-casing "no items".
     */
    @Transactional(readOnly = true)
    public List<FeeStructureItemResponse> itemResponsesFor(FeeStructure structure) {
        List<FeeStructureItem> items =
                feeStructureItemRepository.findByFeeStructureIdOrderBySequenceOrder(structure.getId());
        if (items.isEmpty()) {
            FeeStructureItem implicit = new FeeStructureItem();
            implicit.setFeeStructureId(structure.getId());
            implicit.setCategory(FeeStructureItemCategory.OTHER);
            implicit.setLabel(structure.getName());
            implicit.setAmount(structure.getAmount());
            implicit.setSequenceOrder(0);
            items = List.of(implicit);
        }

        Map<UUID, String> feeTypeNames = new HashMap<>();
        feeTypeRepository.findAllById(items.stream().map(FeeStructureItem::getFeeTypeId).filter(java.util.Objects::nonNull).toList())
                .forEach(t -> feeTypeNames.put(t.getId(), t.getName()));
        return items.stream()
                .map(item -> FeeStructureItemResponse.from(item, feeTypeNames.get(item.getFeeTypeId())))
                .toList();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // --- Invoices ----------------------------------------------------

    /**
     * Generate a PENDING invoice for one student against one fee structure. The
     * amount is copied from the structure, not taken from the request.
     *
     * @throws ApiException 404 if the student or the fee structure does not exist, 409 if the
     *         student already has an invoice against this structure (plan Phase 5 part C: "Prevent
     *         accidental duplicate fee assignments").
     */
    @Transactional
    public Invoice createInvoice(CreateInvoiceRequest request) {
        return createInvoice(request, null);
    }

    @Transactional
    public Invoice createInvoice(CreateInvoiceRequest request, UUID assignedByUserId) {
        if (studentRepository.findById(request.studentId()).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        FeeStructure structure = feeStructureRepository.findById(request.feeStructureId())
                .orElseThrow(() -> new ApiException("Fee structure not found", HttpStatus.NOT_FOUND));
        if (invoiceRepository.existsByStudentIdAndFeeStructureId(request.studentId(), structure.getId())) {
            throw new ApiException("This student already has an invoice for this fee structure", HttpStatus.CONFLICT);
        }

        Invoice invoice = new Invoice();
        invoice.setStudentId(request.studentId());
        invoice.setFeeStructureId(structure.getId());
        invoice.setAmount(structure.getAmount());
        invoice.setNetAmount(structure.getAmount());
        invoice.setStatus(InvoiceStatus.PENDING);
        invoice.setAssignedByUserId(assignedByUserId);
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

        // The payable amount is the current outstanding balance, not the original gross amount --
        // a discount or a prior partial manual payment must be reflected in what Razorpay charges.
        long amountInPaise = invoice.getBalance().multiply(MINOR_UNIT_FACTOR).longValueExact();
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

        BigDecimal amountBeingSettled = invoice.getBalance();
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setRazorpayOrderId(razorpayOrderId);
        invoice.setRazorpayPaymentId(razorpayPaymentId);
        invoice.setPaidAt(OffsetDateTime.now());
        invoice.setPaidAmount(invoice.getNetAmount());
        invoiceRepository.save(invoice);
        recordOnlineLedgerRow(invoice, amountBeingSettled, razorpayPaymentId);

        auditService.logAs(null, AuditActions.INVOICE_PAID, AuditActions.INVOICE, invoice.getId(),
                Map.of("razorpayOrderId", razorpayOrderId, "razorpayPaymentId", razorpayPaymentId));
    }

    /**
     * Inserts the {@link FeePayment} ledger row for a payment settled through the Razorpay webhook
     * or dev-simulate path -- {@link Invoice}'s own status/paidAmount fields above are the
     * authoritative, already-tested mutation; this only ensures the same append-only ledger
     * {@link FeeCollectionService} reads from also has an entry for online payments, so "how much
     * has been collected" is never computed two different ways (plan Phase 5 part H). Skipped when
     * there is nothing left to settle (e.g. a 100%-discounted invoice already at zero balance).
     */
    private void recordOnlineLedgerRow(Invoice invoice, BigDecimal amount, String razorpayPaymentId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        FeePayment payment = new FeePayment();
        payment.setInvoiceId(invoice.getId());
        payment.setType(PaymentType.PAYMENT);
        payment.setAmount(amount);
        payment.setMethod(PaymentMethod.ONLINE);
        payment.setReferenceNumber(razorpayPaymentId);
        payment.setReceiptNumber(paymentRecorder.nextReceiptNumber());
        feePaymentRepository.save(payment);
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

        BigDecimal amountBeingSettled = invoice.getBalance();
        invoice.setStatus(InvoiceStatus.PAID);
        if (invoice.getRazorpayOrderId() == null) {
            invoice.setRazorpayOrderId(SIMULATED_PAYMENT_REF);
        }
        invoice.setRazorpayPaymentId(SIMULATED_PAYMENT_REF);
        invoice.setPaidAt(OffsetDateTime.now());
        invoice.setPaidAmount(invoice.getNetAmount());
        Invoice saved = invoiceRepository.save(invoice);
        recordOnlineLedgerRow(saved, amountBeingSettled, SIMULATED_PAYMENT_REF);

        log.warn("DEV-TOOLS: invoice {} marked PAID via simulate-payment-success (not a real Razorpay webhook)",
                saved.getId());
        auditService.log(AuditActions.INVOICE_PAID_SIMULATED, AuditActions.INVOICE, saved.getId(),
                Map.of("note", "dev-tools simulate-payment-success; not a verified webhook"));
        return saved;
    }
}
