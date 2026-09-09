package com.smsapp.fee;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentRepository;
import com.smsapp.tenant.TenantContext;
import jakarta.persistence.EntityManager;
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
 * Fee structures + invoices + Razorpay checkout. Every read and write is scoped
 * by {@code tenant_id} on top of the database RLS policy (plan section 1). We
 * never store raw payment data -- only Razorpay's own order/payment reference
 * ids (plan section 7.2a).
 */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    /** Razorpay charges in the smallest currency unit; rupees -> paise. */
    private static final BigDecimal MINOR_UNIT_FACTOR = BigDecimal.valueOf(100);

    static final String NOTE_TENANT_ID = "tenantId";
    static final String NOTE_INVOICE_ID = "invoiceId";

    private final FeeStructureRepository feeStructureRepository;
    private final InvoiceRepository invoiceRepository;
    private final SchoolRepository schoolRepository;
    private final StudentRepository studentRepository;
    private final RazorpayGateway razorpayGateway;
    private final RazorpayProperties razorpayProperties;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public FeeService(FeeStructureRepository feeStructureRepository, InvoiceRepository invoiceRepository,
                      SchoolRepository schoolRepository, StudentRepository studentRepository,
                      RazorpayGateway razorpayGateway, RazorpayProperties razorpayProperties,
                      AuditService auditService, EntityManager entityManager) {
        this.feeStructureRepository = feeStructureRepository;
        this.invoiceRepository = invoiceRepository;
        this.schoolRepository = schoolRepository;
        this.studentRepository = studentRepository;
        this.razorpayGateway = razorpayGateway;
        this.razorpayProperties = razorpayProperties;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    // --- Fee structures ------------------------------------------------

    /**
     * @throws ApiException 404 if the school is not in the caller's tenant.
     */
    @Transactional
    public FeeStructure createFeeStructure(UUID tenantId, CreateFeeStructureRequest request) {
        if (!schoolRepository.existsByIdAndTenantId(request.schoolId(), tenantId)) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        FeeStructure structure = new FeeStructure();
        structure.setTenantId(tenantId);
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
    public List<FeeStructure> listFeeStructures(UUID tenantId) {
        return feeStructureRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    // --- Invoices ----------------------------------------------------

    /**
     * Generate a PENDING invoice for one student against one fee structure. The
     * amount is copied from the structure, not taken from the request.
     *
     * @throws ApiException 404 if the student or the fee structure is not in the caller's tenant.
     */
    @Transactional
    public Invoice createInvoice(UUID tenantId, CreateInvoiceRequest request) {
        if (studentRepository.findByIdAndTenantId(request.studentId(), tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        FeeStructure structure = feeStructureRepository.findByIdAndTenantId(request.feeStructureId(), tenantId)
                .orElseThrow(() -> new ApiException("Fee structure not found", HttpStatus.NOT_FOUND));

        Invoice invoice = new Invoice();
        invoice.setTenantId(tenantId);
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
     * @throws ApiException 404 if the student is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<Invoice> listInvoicesForStudent(UUID tenantId, UUID studentId) {
        if (studentRepository.findByIdAndTenantId(studentId, tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        return invoiceRepository.findByTenantIdAndStudentIdOrderByCreatedAtDesc(tenantId, studentId);
    }

    /** Portal use: the caller's ownership of {@code studentId} has already been proven. */
    @Transactional(readOnly = true)
    public List<Invoice> invoicesForOwnedStudent(UUID tenantId, UUID studentId) {
        return invoiceRepository.findByTenantIdAndStudentIdOrderByCreatedAtDesc(tenantId, studentId);
    }

    // --- Checkout --------------------------------------------------

    /**
     * The invoice must belong to the caller's tenant, and -- when the caller is a
     * PARENT rather than staff ({@code parentGuardianUserId} non-null) -- to one of
     * that parent's own children. A mismatch is reported as 404, never 403, so it
     * never leaks that another family's invoice exists (same rule as the portal
     * ownership checks).
     */
    private Invoice loadInvoiceForActor(UUID tenantId, UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        if (parentGuardianUserId != null
                && studentRepository.findByIdAndTenantIdAndGuardianUserId(
                        invoice.getStudentId(), tenantId, parentGuardianUserId).isEmpty()) {
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
     * @throws ApiException 404 if the invoice is not in the caller's tenant (or not
     *         the parent's child), 409 if it is already paid.
     */
    @Transactional
    public CheckoutResponse startCheckout(UUID tenantId, UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = loadInvoiceForActor(tenantId, invoiceId, parentGuardianUserId);
        if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
            throw new ApiException("This invoice is already paid", HttpStatus.CONFLICT);
        }

        long amountInPaise = invoice.getAmount().multiply(MINOR_UNIT_FACTOR).longValueExact();
        String currency = razorpayProperties.currency();
        String orderId = razorpayGateway.createOrder(amountInPaise, currency, invoice.getId().toString(),
                Map.of(NOTE_TENANT_ID, tenantId.toString(), NOTE_INVOICE_ID, invoice.getId().toString()));

        invoice.setRazorpayOrderId(orderId);
        invoiceRepository.save(invoice);

        auditService.log(AuditActions.INVOICE_CHECKOUT_STARTED, AuditActions.INVOICE, invoice.getId(),
                Map.of("razorpayOrderId", orderId, "amountInPaise", amountInPaise));

        return new CheckoutResponse(invoice.getId(), orderId, razorpayGateway.keyId(), amountInPaise, currency);
    }

    // --- Payment webhook ------------------------------------------

    /**
     * Applied only after the webhook signature has been verified by the caller.
     * The {@code tenantId} comes from the (now-trusted) order notes we set at
     * checkout, so this method has to establish its own tenant context -- there
     * is no JWT on a server-to-server webhook. Mirrors {@code AuthService.login}.
     *
     * <p>Idempotent: a duplicate delivery for an already-PAID invoice is a no-op,
     * so a Razorpay retry can never double-apply (plan section 7.2a).
     */
    @Transactional
    public void markInvoicePaid(UUID tenantId, UUID invoiceId, String razorpayOrderId, String razorpayPaymentId) {
        TenantContext.setCurrentTenant(tenantId.toString());
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        Invoice invoice = invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)
                .or(() -> invoiceRepository.findByTenantIdAndRazorpayOrderId(tenantId, razorpayOrderId))
                .orElse(null);
        if (invoice == null) {
            log.warn("Razorpay webhook for unknown invoice tenantId={} invoiceId={} orderId={}",
                    tenantId, invoiceId, razorpayOrderId);
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

        auditService.logAs(tenantId, null, AuditActions.INVOICE_PAID, AuditActions.INVOICE, invoice.getId(),
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
     * <p>Tenant context is already established from the caller's JWT (the request
     * filter), so unlike the webhook path this does not set it manually. Idempotent:
     * a second call on an already-paid invoice is a no-op.
     *
     * @param parentGuardianUserId the caller's user id when the caller is a PARENT
     *        (restricts the invoice to their own children); {@code null} for staff.
     * @throws ApiException 404 if the invoice is not in the caller's tenant (or not
     *         the parent's child).
     */
    @Transactional
    public Invoice simulatePaymentSuccess(UUID tenantId, UUID invoiceId, UUID parentGuardianUserId) {
        Invoice invoice = loadInvoiceForActor(tenantId, invoiceId, parentGuardianUserId);
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
