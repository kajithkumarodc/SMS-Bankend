package com.smsapp.fee;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.BulkAssignResult;
import com.smsapp.fee.FeeDtos.CreateFeeDiscountRequest;
import com.smsapp.fee.FeeDtos.InvoiceStatementLine;
import com.smsapp.fee.FeeDtos.PaymentResponse;
import com.smsapp.fee.FeeDtos.ReceiptResponse;
import com.smsapp.fee.FeeDtos.StudentFeeStatementResponse;
import com.smsapp.school.School;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fee assignment/discount/collection/refund workflow (plan Phase 5 parts C-G): everything
 * built on top of the existing {@link Invoice}/{@link FeeStructure} model from {@link FeeService},
 * which keeps structure/invoice creation and the Razorpay checkout/webhook flow unchanged.
 * {@link FeePaymentRecorder} is the only place a payment is actually applied to an invoice, so this
 * service and the webhook path can never disagree about how much has been collected.
 */
@Service
public class FeeCollectionService {

    private final InvoiceRepository invoiceRepository;
    private final FeeStructureRepository feeStructureRepository;
    private final FeeDiscountRepository feeDiscountRepository;
    private final FeePaymentRepository feePaymentRepository;
    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;
    private final FeePaymentRecorder paymentRecorder;
    private final AuditService auditService;

    public FeeCollectionService(InvoiceRepository invoiceRepository, FeeStructureRepository feeStructureRepository,
                                FeeDiscountRepository feeDiscountRepository, FeePaymentRepository feePaymentRepository,
                                StudentRepository studentRepository, SchoolRepository schoolRepository,
                                ClassRepository classRepository, SectionRepository sectionRepository,
                                UserRepository userRepository, FeePaymentRecorder paymentRecorder,
                                AuditService auditService) {
        this.invoiceRepository = invoiceRepository;
        this.feeStructureRepository = feeStructureRepository;
        this.feeDiscountRepository = feeDiscountRepository;
        this.feePaymentRepository = feePaymentRepository;
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.paymentRecorder = paymentRecorder;
        this.auditService = auditService;
    }

    // --- Bulk assignment (plan part C) ----------------------------------

    public record BulkAssignOutcome(List<BulkAssignResult> results, int assignedCount) {
    }

    /**
     * Raises a PENDING invoice for every id in {@code studentIds} against {@code feeStructureId}; a
     * student not found is skipped, and one already invoiced against this structure is skipped
     * rather than duplicated (plan: "Prevent accidental duplicate fee assignments").
     *
     * @throws ApiException 404 if the fee structure does not exist.
     */
    @Transactional
    public BulkAssignOutcome bulkAssign(UUID feeStructureId, Set<UUID> studentIds, UUID actorUserId) {
        FeeStructure structure = feeStructureRepository.findById(feeStructureId)
                .orElseThrow(() -> new ApiException("Fee structure not found", HttpStatus.NOT_FOUND));

        List<UUID> idList = new ArrayList<>(studentIds);
        Set<UUID> alreadyInvoiced = new HashSet<>(invoiceRepository.studentIdsAlreadyInvoiced(feeStructureId, idList));
        Map<UUID, Student> studentsById = new HashMap<>();
        studentRepository.findAllById(studentIds).forEach(s -> studentsById.put(s.getId(), s));

        List<BulkAssignResult> results = new ArrayList<>();
        List<Invoice> toSave = new ArrayList<>();
        for (UUID studentId : studentIds) {
            if (!studentsById.containsKey(studentId)) {
                results.add(new BulkAssignResult(studentId, false, "Student not found"));
                continue;
            }
            if (alreadyInvoiced.contains(studentId)) {
                results.add(new BulkAssignResult(studentId, false, "Already assigned this fee"));
                continue;
            }
            Invoice invoice = new Invoice();
            invoice.setStudentId(studentId);
            invoice.setFeeStructureId(structure.getId());
            invoice.setAmount(structure.getAmount());
            invoice.setNetAmount(structure.getAmount());
            invoice.setStatus(InvoiceStatus.PENDING);
            invoice.setAssignedByUserId(actorUserId);
            toSave.add(invoice);
            results.add(new BulkAssignResult(studentId, true, null));
        }
        invoiceRepository.saveAll(toSave);

        auditService.log(AuditActions.INVOICES_BULK_ASSIGNED, AuditActions.FEE_STRUCTURE, structure.getId(),
                Map.of("assignedCount", toSave.size(), "requestedCount", studentIds.size()));
        return new BulkAssignOutcome(results, toSave.size());
    }

    // --- Discounts (plan part D) -----------------------------------------

    @Transactional(readOnly = true)
    public List<FeeDiscount> listDiscounts() {
        return feeDiscountRepository.findAllByOrderByName();
    }

    /** @throws ApiException 400 for an invalid discount type/percentage. */
    @Transactional
    public FeeDiscount createDiscount(CreateFeeDiscountRequest request) {
        if (!FeeDiscountType.isValid(request.discountType())) {
            throw new ApiException("Not a recognized discount type: " + request.discountType(), HttpStatus.BAD_REQUEST);
        }
        if (FeeDiscountType.PERCENTAGE.equals(request.discountType()) && request.value().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ApiException("A percentage discount cannot exceed 100", HttpStatus.BAD_REQUEST);
        }
        if (request.feeStructureId() != null && !feeStructureRepository.existsById(request.feeStructureId())) {
            throw new ApiException("Fee structure not found", HttpStatus.NOT_FOUND);
        }

        FeeDiscount discount = new FeeDiscount();
        discount.setName(request.name().trim());
        discount.setDiscountType(request.discountType());
        discount.setValue(request.value());
        discount.setFeeStructureId(request.feeStructureId());
        discount.setValidFrom(request.validFrom());
        discount.setValidTo(request.validTo());
        FeeDiscount saved = feeDiscountRepository.save(discount);

        auditService.log(AuditActions.FEE_DISCOUNT_CREATED, AuditActions.FEE_DISCOUNT, saved.getId(),
                Map.of("name", saved.getName(), "discountType", saved.getDiscountType()));
        return saved;
    }

    /**
     * Applies a discount to an invoice that has not yet received any payment (changing the payable
     * base after collection has begun would make the already-collected amount inconsistent with a
     * shrunken total, so it is rejected outright rather than guessed at).
     *
     * @throws ApiException 404 if the invoice/discount does not exist, 409 if the invoice already has
     *         a payment, 400 if the discount is inactive, out of its validity window, or does not
     *         apply to this invoice's fee structure.
     */
    @Transactional
    public Invoice applyDiscount(UUID invoiceId, UUID discountId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new ApiException("Cannot apply a discount after a payment has been collected", HttpStatus.CONFLICT);
        }
        FeeDiscount discount = feeDiscountRepository.findById(discountId)
                .orElseThrow(() -> new ApiException("Discount not found", HttpStatus.NOT_FOUND));
        if (!FeeStructureStatus.ACTIVE.equals(discount.getStatus())) {
            throw new ApiException("This discount is not active", HttpStatus.BAD_REQUEST);
        }
        if (discount.getFeeStructureId() != null && !discount.getFeeStructureId().equals(invoice.getFeeStructureId())) {
            throw new ApiException("This discount does not apply to this invoice's fee structure", HttpStatus.BAD_REQUEST);
        }
        LocalDate today = LocalDate.now();
        if ((discount.getValidFrom() != null && today.isBefore(discount.getValidFrom()))
                || (discount.getValidTo() != null && today.isAfter(discount.getValidTo()))) {
            throw new ApiException("This discount is not currently valid", HttpStatus.BAD_REQUEST);
        }

        BigDecimal discountAmount = computeDiscountAmount(discount, invoice.getAmount());
        invoice.setDiscountId(discount.getId());
        invoice.setDiscountAmount(discountAmount);
        invoice.setNetAmount(invoice.getAmount().subtract(discountAmount).add(invoice.getLateFeeAmount()));
        InvoiceStatusCalculator.apply(invoice);
        Invoice saved = invoiceRepository.save(invoice);

        auditService.log(AuditActions.FEE_DISCOUNT_APPLIED, AuditActions.INVOICE, invoice.getId(),
                Map.of("discountId", discount.getId().toString(), "discountAmount", discountAmount.toPlainString()));
        return saved;
    }

    private static BigDecimal computeDiscountAmount(FeeDiscount discount, BigDecimal grossAmount) {
        BigDecimal raw = FeeDiscountType.PERCENTAGE.equals(discount.getDiscountType())
                ? grossAmount.multiply(discount.getValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : discount.getValue();
        // Never allow a discount greater than the fee it applies to (plan part D).
        return raw.min(grossAmount);
    }

    // --- Late fee (plan part B: "optional late fee configuration") -------

    /**
     * @throws ApiException 404 if the invoice does not exist, 400 if no late fee is configured on
     *         its structure or the invoice is not yet overdue, 409 if already applied.
     */
    @Transactional
    public Invoice applyLateFee(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        if (invoice.isLateFeeApplied()) {
            throw new ApiException("The late fee has already been applied to this invoice", HttpStatus.CONFLICT);
        }
        FeeStructure structure = feeStructureRepository.findById(invoice.getFeeStructureId())
                .orElseThrow(() -> new ApiException("Fee structure not found", HttpStatus.NOT_FOUND));
        if (structure.getLateFeeAmount() == null) {
            throw new ApiException("No late fee is configured for this fee structure", HttpStatus.BAD_REQUEST);
        }
        if (!LocalDate.now().isAfter(structure.getDueDate())) {
            throw new ApiException("This invoice is not yet overdue", HttpStatus.BAD_REQUEST);
        }

        invoice.setLateFeeAmount(structure.getLateFeeAmount());
        invoice.setLateFeeApplied(true);
        invoice.setNetAmount(invoice.getAmount().subtract(invoice.getDiscountAmount()).add(structure.getLateFeeAmount()));
        InvoiceStatusCalculator.apply(invoice);
        Invoice saved = invoiceRepository.save(invoice);

        auditService.log(AuditActions.FEE_LATE_FEE_APPLIED, AuditActions.INVOICE, invoice.getId(),
                Map.of("lateFeeAmount", structure.getLateFeeAmount().toPlainString()));
        return saved;
    }

    // --- Collection (plan part E) ----------------------------------------

    /**
     * Collects a manual payment (CASH/BANK_TRANSFER/CHEQUE/OTHER) against an invoice's current
     * balance. The invoice row is locked for the duration of this transaction so two concurrent
     * collection attempts against the same invoice can never both succeed against a balance that
     * only covers one of them (plan part N).
     *
     * @throws ApiException 404 if the invoice does not exist, 400 for an invalid method or an amount
     *         exceeding the outstanding balance, 409 if the invoice is already fully paid.
     */
    @Transactional
    public FeePayment collectPayment(UUID invoiceId, BigDecimal amount, String method, String referenceNumber,
                                     String notes, UUID collectedByUserId) {
        if (!PaymentMethod.isValidManual(method)) {
            throw new ApiException("Not a valid manual payment method: " + method, HttpStatus.BAD_REQUEST);
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        BigDecimal balance = invoice.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("This invoice is already fully paid", HttpStatus.CONFLICT);
        }
        // Authoritative server-side check (plan part N): a client-supplied amount greater than the
        // real outstanding balance is rejected outright, never trusted.
        if (amount.compareTo(balance) > 0) {
            throw new ApiException(
                    "Payment amount (" + amount + ") exceeds the outstanding balance (" + balance + ")",
                    HttpStatus.BAD_REQUEST);
        }
        return paymentRecorder.record(invoice, amount, method, referenceNumber, collectedByUserId, notes);
    }

    // --- Refund / reversal (plan part G) ---------------------------------

    /**
     * Reverses a previously-collected payment. Never deletes or edits the original row -- the
     * reversal is its own new ledger entry referencing it (plan: "never silently delete a financial
     * transaction").
     *
     * @throws ApiException 404 if the payment does not exist, 400 if it is itself a reversal, 409 if
     *         it has already been reversed.
     */
    @Transactional
    public FeePayment reversePayment(UUID paymentId, String reason, UUID actorUserId) {
        FeePayment original = feePaymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException("Payment not found", HttpStatus.NOT_FOUND));
        if (!PaymentType.PAYMENT.equals(original.getType())) {
            throw new ApiException("Only a payment can be reversed", HttpStatus.BAD_REQUEST);
        }
        if (feePaymentRepository.existsByReversesPaymentIdAndType(paymentId, PaymentType.REVERSAL)) {
            throw new ApiException("This payment has already been reversed", HttpStatus.CONFLICT);
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(original.getInvoiceId())
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));

        FeePayment reversal = new FeePayment();
        reversal.setInvoiceId(original.getInvoiceId());
        reversal.setType(PaymentType.REVERSAL);
        reversal.setAmount(original.getAmount());
        reversal.setMethod(original.getMethod());
        reversal.setReceiptNumber(paymentRecorder.nextReceiptNumber());
        reversal.setCollectedByUserId(actorUserId);
        reversal.setReversesPaymentId(original.getId());
        reversal.setReason(reason);
        FeePayment savedReversal = feePaymentRepository.save(reversal);

        invoice.setPaidAmount(invoice.getPaidAmount().subtract(original.getAmount()).max(BigDecimal.ZERO));
        InvoiceStatusCalculator.apply(invoice);
        invoiceRepository.save(invoice);

        auditService.log(AuditActions.FEE_PAYMENT_REVERSED, AuditActions.FEE_PAYMENT, savedReversal.getId(),
                Map.of("reversesPaymentId", original.getId().toString(), "amount", original.getAmount().toPlainString(),
                        "reason", reason));
        return savedReversal;
    }

    // --- Reads: payment history, receipt, statement -----------------------

    @Transactional(readOnly = true)
    public List<FeePayment> paymentsFor(UUID invoiceId) {
        return feePaymentRepository.findByInvoiceIdOrderByPaidAtDesc(invoiceId);
    }

    /**
     * @param parentGuardianUserId the caller's user id when acting as a PARENT (restricts the
     *        receipt to their own child); {@code null} for staff.
     * @throws ApiException 404 if the payment does not exist or (for a parent) is not their child's.
     */
    @Transactional(readOnly = true)
    public ReceiptResponse receiptFor(UUID paymentId, UUID parentGuardianUserId) {
        FeePayment payment = feePaymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException("Payment not found", HttpStatus.NOT_FOUND));
        Invoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ApiException("Invoice not found", HttpStatus.NOT_FOUND));
        Student student = (parentGuardianUserId != null
                ? studentRepository.findByIdAndGuardianUserId(invoice.getStudentId(), parentGuardianUserId)
                : studentRepository.findById(invoice.getStudentId()))
                .orElseThrow(() -> new ApiException("Payment not found", HttpStatus.NOT_FOUND));

        FeeStructure structure = feeStructureRepository.findById(invoice.getFeeStructureId()).orElse(null);
        String schoolName = schoolRepository.findById(student.getSchoolId()).map(School::getName).orElse(null);
        String className = null;
        String sectionName = null;
        if (student.getSectionId() != null) {
            Section section = sectionRepository.findById(student.getSectionId()).orElse(null);
            if (section != null) {
                sectionName = section.getName();
                className = classRepository.findById(section.getClassId()).map(SchoolClass::getName).orElse(null);
            }
        }
        String collectedByName = payment.getCollectedByUserId() == null ? null
                : userRepository.findById(payment.getCollectedByUserId()).map(User::getFullName).orElse(null);

        return new ReceiptResponse(
                PaymentResponse.from(payment),
                invoice.getId(),
                schoolName,
                student.getFullName(),
                student.getAdmissionNumber(),
                className,
                sectionName,
                structure == null ? null : structure.getAcademicYear(),
                structure == null ? null : structure.getName(),
                invoice.getAmount(),
                invoice.getDiscountAmount(),
                invoice.getLateFeeAmount(),
                balanceAfter(invoice, payment),
                collectedByName);
    }

    /** The invoice's outstanding balance immediately after {@code payment}, replaying the ledger in chronological order. */
    private BigDecimal balanceAfter(Invoice invoice, FeePayment payment) {
        List<FeePayment> chronological =
                feePaymentRepository.findByInvoiceIdOrderByPaidAtDesc(invoice.getId()).reversed();
        BigDecimal runningPaid = BigDecimal.ZERO;
        for (FeePayment p : chronological) {
            runningPaid = PaymentType.PAYMENT.equals(p.getType())
                    ? runningPaid.add(p.getAmount())
                    : runningPaid.subtract(p.getAmount());
            if (p.getId().equals(payment.getId())) {
                return invoice.getNetAmount().subtract(runningPaid);
            }
        }
        return invoice.getBalance();
    }

    /**
     * @param parentGuardianUserId the caller's user id when acting as a PARENT (restricts the
     *        statement to their own child); {@code null} for staff.
     * @throws ApiException 404 if the student does not exist or (for a parent) is not their child.
     */
    @Transactional(readOnly = true)
    public StudentFeeStatementResponse studentStatement(UUID studentId, UUID parentGuardianUserId) {
        if (parentGuardianUserId != null) {
            studentRepository.findByIdAndGuardianUserId(studentId, parentGuardianUserId)
                    .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
        } else if (studentRepository.findById(studentId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }

        List<Invoice> invoices = invoiceRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
        Map<UUID, FeeStructure> structures = new HashMap<>();
        feeStructureRepository.findAllById(invoices.stream().map(Invoice::getFeeStructureId).toList())
                .forEach(s -> structures.put(s.getId(), s));

        BigDecimal totalAssigned = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalPayable = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalBalance = BigDecimal.ZERO;
        List<InvoiceStatementLine> lines = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Invoice invoice : invoices) {
            FeeStructure structure = structures.get(invoice.getFeeStructureId());
            boolean overdue = structure != null && today.isAfter(structure.getDueDate())
                    && invoice.getBalance().compareTo(BigDecimal.ZERO) > 0;
            List<PaymentResponse> payments = feePaymentRepository.findByInvoiceIdOrderByPaidAtDesc(invoice.getId())
                    .stream().map(PaymentResponse::from).toList();

            lines.add(new InvoiceStatementLine(invoice.getId(), structure == null ? null : structure.getName(),
                    structure == null ? null : structure.getDueDate(), invoice.getAmount(), invoice.getDiscountAmount(),
                    invoice.getNetAmount(), invoice.getPaidAmount(), invoice.getBalance(), invoice.getStatus(),
                    overdue, payments));

            totalAssigned = totalAssigned.add(invoice.getAmount());
            totalDiscount = totalDiscount.add(invoice.getDiscountAmount());
            totalPayable = totalPayable.add(invoice.getNetAmount());
            totalPaid = totalPaid.add(invoice.getPaidAmount());
            totalBalance = totalBalance.add(invoice.getBalance());
        }

        return new StudentFeeStatementResponse(studentId, totalAssigned, totalDiscount, totalPayable, totalPaid,
                totalBalance, lines);
    }
}
