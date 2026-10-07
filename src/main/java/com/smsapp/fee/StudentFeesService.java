package com.smsapp.fee;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import com.smsapp.common.ApiException;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Student Fees screen (Collect Fees): every bill of a student broken into fee lines, and collection receipts. */
@Service
public class StudentFeesService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InvoiceRepository invoiceRepository;
    private final FeeStructureRepository structureRepository;
    private final FeeLineService lineService;
    private final FeePaymentRepository paymentRepository;
    private final FeePaymentAllocationRepository allocationRepository;
    private final InvoiceLineRepository lineRepository;
    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final ClassRepository classRepository;
    private final SchoolRepository schoolRepository;
    private final UserRepository userRepository;

    public StudentFeesService(InvoiceRepository invoiceRepository, FeeStructureRepository structureRepository,
                              FeeLineService lineService, FeePaymentRepository paymentRepository,
                              FeePaymentAllocationRepository allocationRepository, InvoiceLineRepository lineRepository,
                              StudentRepository studentRepository, SectionRepository sectionRepository,
                              ClassRepository classRepository, SchoolRepository schoolRepository,
                              UserRepository userRepository) {
        this.invoiceRepository = invoiceRepository;
        this.structureRepository = structureRepository;
        this.lineService = lineService;
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.lineRepository = lineRepository;
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.classRepository = classRepository;
        this.schoolRepository = schoolRepository;
        this.userRepository = userRepository;
    }

    public record PaymentRow(UUID paymentId, String paymentNumber, String receiptNumber, String method,
                             LocalDate paymentDate, BigDecimal amount, BigDecimal fine, UUID collectionId,
                             String referenceNumber, String notes, boolean reversed) {
    }

    public record LineRow(UUID lineId, UUID invoiceId, String feeGroup, String label, String term, LocalDate dueDate,
                          BigDecimal amount, BigDecimal discount, BigDecimal fine, BigDecimal paid, BigDecimal balance,
                          String status, boolean overdue, List<PaymentRow> payments) {
    }

    public record GroupRow(UUID invoiceId, UUID feeStructureId, String name, String academicYear, BigDecimal amount,
                           BigDecimal discount, BigDecimal fine, BigDecimal paid, BigDecimal balance, String status) {
    }

    public record Totals(BigDecimal amount, BigDecimal discount, BigDecimal fine, BigDecimal paid, BigDecimal balance) {
    }

    public record StudentFeesView(UUID studentId, List<GroupRow> groups, List<LineRow> lines, Totals totals) {
    }

    /** @throws ApiException 404 if the student does not exist. */
    @Transactional(readOnly = true)
    public StudentFeesView view(UUID studentId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        List<Invoice> invoices = invoiceRepository.findByStudentIdOrderByCreatedAtDesc(studentId).reversed();
        Map<UUID, FeeStructure> structures = new HashMap<>();
        structureRepository.findAllById(invoices.stream().map(Invoice::getFeeStructureId).toList())
                .forEach(s -> structures.put(s.getId(), s));
        Map<UUID, List<FeeLineService.LineState>> ledger = lineService.ledger(invoices);
        LocalDate today = LocalDate.now();

        List<GroupRow> groups = new ArrayList<>();
        List<LineRow> lines = new ArrayList<>();
        BigDecimal amount = ZERO, discount = ZERO, fine = ZERO, paid = ZERO, balance = ZERO;
        for (Invoice invoice : invoices) {
            FeeStructure structure = structures.get(invoice.getFeeStructureId());
            String name = structure == null ? "Fees" : structure.getName();
            groups.add(new GroupRow(invoice.getId(), invoice.getFeeStructureId(), name,
                    structure == null ? null : structure.getAcademicYear(), invoice.getAmount(),
                    invoice.getDiscountAmount(), invoice.getLateFeeAmount(), invoice.getPaidAmount(),
                    invoice.getBalance().max(ZERO), invoice.getStatus()));
            for (FeeLineService.LineState s : ledger.getOrDefault(invoice.getId(), List.of())) {
                InvoiceLine line = s.line();
                List<PaymentRow> payments = s.payments().stream().map(p -> new PaymentRow(p.paymentId(),
                        p.receiptNumber() + "/" + p.index(), p.receiptNumber(), p.method(), p.paymentDate(),
                        p.amount(), p.fine(), p.collectionId(), p.referenceNumber(), p.notes(), p.reversed())).toList();
                lines.add(new LineRow(line.getId(), invoice.getId(), name, line.getLabel(), line.getCategory(),
                        line.getDueDate(), line.getAmount(), line.getDiscountAmount(), s.fine(), s.paid(), s.balance(),
                        s.status(), s.balance().signum() > 0 && line.getDueDate().isBefore(today), payments));
                fine = fine.add(s.fine());
            }
            amount = amount.add(invoice.getAmount());
            discount = discount.add(invoice.getDiscountAmount());
            paid = paid.add(invoice.getPaidAmount());
            balance = balance.add(invoice.getBalance().max(ZERO));
        }
        lines.sort(Comparator.comparing(LineRow::dueDate).thenComparing(LineRow::feeGroup));
        return new StudentFeesView(studentId, groups, lines, new Totals(amount, discount, fine, paid, balance));
    }

    public record ReceiptLine(String feeGroup, String label, String term, LocalDate dueDate, BigDecimal amount,
                              BigDecimal fine, BigDecimal balanceAfter) {
    }

    public record CollectionReceipt(UUID collectionId, List<String> receiptNumbers, LocalDate paymentDate,
                                    String method, String referenceNumber, String notes, String collectedBy,
                                    String schoolName, UUID studentId, String studentName, String admissionNumber,
                                    String className, String sectionName, String fatherName, List<ReceiptLine> lines,
                                    BigDecimal totalAmount, BigDecimal totalFine, BigDecimal total, boolean reversed) {
    }

    /** Everything collected in one "Collect Fees", for printing. 404 if unknown. */
    @Transactional(readOnly = true)
    public CollectionReceipt receipt(UUID collectionId) {
        List<FeePayment> payments = paymentRepository.findByCollectionIdOrderByPaidAtAsc(collectionId);
        if (payments.isEmpty()) {
            throw new ApiException("Receipt not found", HttpStatus.NOT_FOUND);
        }
        FeePayment first = payments.get(0);
        Invoice firstInvoice = invoiceRepository.findById(first.getInvoiceId())
                .orElseThrow(() -> new ApiException("Receipt not found", HttpStatus.NOT_FOUND));
        Student student = studentRepository.findById(firstInvoice.getStudentId())
                .orElseThrow(() -> new ApiException("Receipt not found", HttpStatus.NOT_FOUND));

        List<Invoice> invoices = payments.stream().map(FeePayment::getInvoiceId).distinct()
                .map(id -> invoiceRepository.findById(id).orElseThrow()).toList();
        Map<UUID, FeeLineService.LineState> states = new HashMap<>();
        lineService.ledger(invoices).values().forEach(l -> l.forEach(s -> states.put(s.line().getId(), s)));
        Map<UUID, String> structureNames = new HashMap<>();
        structureRepository.findAllById(invoices.stream().map(Invoice::getFeeStructureId).toList())
                .forEach(s -> structureNames.put(s.getId(), s.getName()));
        Map<UUID, UUID> structureOfInvoice = new HashMap<>();
        invoices.forEach(i -> structureOfInvoice.put(i.getId(), i.getFeeStructureId()));

        List<ReceiptLine> lines = new ArrayList<>();
        BigDecimal totalAmount = ZERO, totalFine = ZERO;
        boolean reversed = false;
        for (FeePayment payment : payments) {
            reversed |= paymentRepository.existsByReversesPaymentIdAndType(payment.getId(), PaymentType.REVERSAL);
            for (FeePaymentAllocation a : allocationRepository.findByPaymentId(payment.getId())) {
                FeeLineService.LineState s = states.get(a.getInvoiceLineId());
                InvoiceLine line = s != null ? s.line() : lineRepository.findById(a.getInvoiceLineId()).orElseThrow();
                lines.add(new ReceiptLine(structureNames.get(structureOfInvoice.get(payment.getInvoiceId())),
                        line.getLabel(), line.getCategory(), line.getDueDate(), a.getAmount(), a.getFineAmount(),
                        s == null ? null : s.balance()));
                totalAmount = totalAmount.add(a.getAmount());
                totalFine = totalFine.add(a.getFineAmount());
            }
        }

        String className = null;
        String sectionName = null;
        if (student.getSectionId() != null) {
            Section section = sectionRepository.findById(student.getSectionId()).orElse(null);
            if (section != null) {
                sectionName = section.isDefaultSection() ? null : section.getName();
                className = classRepository.findById(section.getClassId()).map(SchoolClass::getName).orElse(null);
            }
        }
        String collectedBy = first.getCollectedByUserId() == null ? null
                : userRepository.findById(first.getCollectedByUserId()).map(User::getFullName).orElse(null);
        String schoolName = schoolRepository.findById(student.getSchoolId()).map(School::getName).orElse(null);
        return new CollectionReceipt(collectionId, payments.stream().map(FeePayment::getReceiptNumber).toList(),
                first.getPaymentDate(), first.getMethod(), first.getReferenceNumber(), first.getNotes(), collectedBy,
                schoolName, student.getId(), student.getFullName(), student.getAdmissionNumber(), className, sectionName,
                student.getFatherName(), lines, totalAmount, totalFine, totalAmount.add(totalFine), reversed);
    }
}
