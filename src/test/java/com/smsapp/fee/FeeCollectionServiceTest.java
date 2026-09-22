package com.smsapp.fee;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CreateFeeDiscountRequest;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeeCollectionServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private FeeStructureRepository feeStructureRepository;

    @Mock
    private FeeDiscountRepository feeDiscountRepository;

    @Mock
    private FeePaymentRepository feePaymentRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FeePaymentRecorder paymentRecorder;

    @Mock
    private AuditService auditService;

    private final UUID invoiceId = UUID.randomUUID();
    private final UUID feeStructureId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private FeeCollectionService service() {
        return new FeeCollectionService(invoiceRepository, feeStructureRepository, feeDiscountRepository,
                feePaymentRepository, studentRepository, schoolRepository, classRepository, sectionRepository,
                userRepository, paymentRecorder, auditService);
    }

    private Invoice invoice(BigDecimal amount, BigDecimal netAmount, BigDecimal paidAmount) {
        Invoice i = new Invoice();
        i.setId(invoiceId);
        i.setStudentId(studentId);
        i.setFeeStructureId(feeStructureId);
        i.setAmount(amount);
        i.setNetAmount(netAmount);
        i.setPaidAmount(paidAmount);
        i.setDiscountAmount(BigDecimal.ZERO);
        i.setLateFeeAmount(BigDecimal.ZERO);
        i.setStatus(InvoiceStatus.PENDING);
        return i;
    }

    private FeeStructure structure(BigDecimal amount, LocalDate dueDate, BigDecimal lateFeeAmount) {
        FeeStructure s = new FeeStructure();
        s.setId(feeStructureId);
        s.setAmount(amount);
        s.setDueDate(dueDate);
        s.setLateFeeAmount(lateFeeAmount);
        return s;
    }

    // --- Bulk assign ---------------------------------------------------

    @Test
    void bulkAssignSkipsAStudentAlreadyInvoicedAndAMissingStudent() {
        UUID alreadyInvoiced = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        when(feeStructureRepository.findById(feeStructureId)).thenReturn(Optional.of(structure(new BigDecimal("100.00"), LocalDate.now().plusDays(30), null)));
        when(invoiceRepository.studentIdsAlreadyInvoiced(org.mockito.ArgumentMatchers.eq(feeStructureId), any()))
                .thenReturn(List.of(alreadyInvoiced));
        Student freshStudent = new Student();
        freshStudent.setId(fresh);
        Student alreadyInvoicedStudent = new Student();
        alreadyInvoicedStudent.setId(alreadyInvoiced);
        when(studentRepository.findAllById(Set.of(alreadyInvoiced, missing, fresh)))
                .thenReturn(List.of(freshStudent, alreadyInvoicedStudent));

        FeeCollectionService.BulkAssignOutcome outcome =
                service().bulkAssign(feeStructureId, Set.of(alreadyInvoiced, missing, fresh), actorId);

        assertThat(outcome.assignedCount()).isEqualTo(1);
        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.results().stream().filter(r -> r.studentId().equals(fresh)).findFirst().get().assigned()).isTrue();
        assertThat(outcome.results().stream().filter(r -> r.studentId().equals(alreadyInvoiced)).findFirst().get().reason())
                .isEqualTo("Already assigned this fee");
        assertThat(outcome.results().stream().filter(r -> r.studentId().equals(missing)).findFirst().get().reason())
                .isEqualTo("Student not found");
    }

    @Test
    void bulkAssignRejectsAnUnknownFeeStructureWith404() {
        when(feeStructureRepository.findById(feeStructureId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().bulkAssign(feeStructureId, Set.of(studentId), actorId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Discounts -------------------------------------------------------

    @Test
    void createDiscountRejectsAPercentageOver100() {
        assertThatThrownBy(() -> service().createDiscount(
                new CreateFeeDiscountRequest("Too much", FeeDiscountType.PERCENTAGE, new BigDecimal("150"), null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createDiscountRejectsAnUnrecognizedType() {
        assertThatThrownBy(() -> service().createDiscount(
                new CreateFeeDiscountRequest("Bad type", "NOT_A_TYPE", BigDecimal.TEN, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void applyDiscountComputesAPercentageAndCapsAtTheFeeAmount() {
        Invoice inv = invoice(new BigDecimal("1000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));
        FeeDiscount discount = new FeeDiscount();
        discount.setId(UUID.randomUUID());
        discount.setDiscountType(FeeDiscountType.PERCENTAGE);
        discount.setValue(new BigDecimal("150")); // deliberately absurd, to prove capping at the fee amount
        discount.setStatus(FeeStructureStatus.ACTIVE);
        when(feeDiscountRepository.findById(discount.getId())).thenReturn(Optional.of(discount));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        Invoice result = service().applyDiscount(invoiceId, discount.getId());

        assertThat(result.getDiscountAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("0.00");
        assertThat(result.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void applyDiscountRejectsWhenAPaymentAlreadyExistsWith409() {
        Invoice inv = invoice(new BigDecimal("1000.00"), new BigDecimal("1000.00"), new BigDecimal("200.00"));
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().applyDiscount(invoiceId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(feeDiscountRepository, never()).findById(any());
    }

    @Test
    void applyDiscountRejectsAnInactiveDiscount() {
        Invoice inv = invoice(new BigDecimal("1000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));
        FeeDiscount discount = new FeeDiscount();
        discount.setId(UUID.randomUUID());
        discount.setDiscountType(FeeDiscountType.FIXED);
        discount.setValue(BigDecimal.TEN);
        discount.setStatus(FeeStructureStatus.INACTIVE);
        when(feeDiscountRepository.findById(discount.getId())).thenReturn(Optional.of(discount));

        assertThatThrownBy(() -> service().applyDiscount(invoiceId, discount.getId()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Late fee ----------------------------------------------------

    @Test
    void applyLateFeeRejectsWhenNotYetOverdue() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("500.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));
        when(feeStructureRepository.findById(feeStructureId))
                .thenReturn(Optional.of(structure(new BigDecimal("500.00"), LocalDate.now().plusDays(5), new BigDecimal("50.00"))));

        assertThatThrownBy(() -> service().applyLateFee(invoiceId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void applyLateFeeAddsTheConfiguredAmountOnceWhenOverdue() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("500.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));
        when(feeStructureRepository.findById(feeStructureId))
                .thenReturn(Optional.of(structure(new BigDecimal("500.00"), LocalDate.now().minusDays(5), new BigDecimal("50.00"))));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        Invoice result = service().applyLateFee(invoiceId);

        assertThat(result.getLateFeeAmount()).isEqualByComparingTo("50.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("550.00");
        assertThat(result.isLateFeeApplied()).isTrue();
    }

    @Test
    void applyLateFeeRejectsASecondApplicationWith409() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("550.00"), BigDecimal.ZERO);
        inv.setLateFeeApplied(true);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().applyLateFee(invoiceId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    // --- Collection ------------------------------------------------------

    @Test
    void collectPaymentRejectsAnAmountExceedingTheBalance() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("300.00")); // balance 200
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().collectPayment(
                invoiceId, new BigDecimal("500.00"), PaymentMethod.CASH, null, null, actorId))
                .isInstanceOf(ApiException.class);

        verify(paymentRecorder, never()).record(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void collectPaymentRejectsAnInvalidMethod() {
        assertThatThrownBy(() -> service().collectPayment(
                invoiceId, BigDecimal.TEN, "BITCOIN", null, null, actorId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(invoiceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void collectPaymentRejectsWhenAlreadyFullyPaidWith409() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("500.00"));
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().collectPayment(
                invoiceId, new BigDecimal("10.00"), PaymentMethod.CASH, null, null, actorId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void collectPaymentDelegatesToTheRecorderWhenWithinBalance() {
        Invoice inv = invoice(new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("100.00"));
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));

        service().collectPayment(invoiceId, new BigDecimal("400.00"), PaymentMethod.CASH, "ref-1", "note", actorId);

        verify(paymentRecorder).record(inv, new BigDecimal("400.00"), PaymentMethod.CASH, "ref-1", actorId, "note");
    }

    // --- Reversal ----------------------------------------------------

    @Test
    void reversePaymentRejectsReversingAReversal() {
        FeePayment reversal = new FeePayment();
        reversal.setId(UUID.randomUUID());
        reversal.setType(PaymentType.REVERSAL);
        when(feePaymentRepository.findById(reversal.getId())).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service().reversePayment(reversal.getId(), "oops", actorId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reversePaymentRejectsADoubleReversalWith409() {
        FeePayment payment = new FeePayment();
        payment.setId(UUID.randomUUID());
        payment.setType(PaymentType.PAYMENT);
        when(feePaymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(feePaymentRepository.existsByReversesPaymentIdAndType(payment.getId(), PaymentType.REVERSAL)).thenReturn(true);

        assertThatThrownBy(() -> service().reversePayment(payment.getId(), "dup", actorId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reversePaymentSubtractsFromPaidAmountAndRestoresPendingStatus() {
        FeePayment payment = new FeePayment();
        payment.setId(UUID.randomUUID());
        payment.setInvoiceId(invoiceId);
        payment.setType(PaymentType.PAYMENT);
        payment.setAmount(new BigDecimal("300.00"));
        payment.setMethod(PaymentMethod.CASH);
        when(feePaymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(feePaymentRepository.existsByReversesPaymentIdAndType(payment.getId(), PaymentType.REVERSAL)).thenReturn(false);
        Invoice inv = invoice(new BigDecimal("300.00"), new BigDecimal("300.00"), new BigDecimal("300.00"));
        inv.setStatus(InvoiceStatus.PAID);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(inv));
        when(feePaymentRepository.save(any(FeePayment.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentRecorder.nextReceiptNumber()).thenReturn("RCPT-000099");

        FeePayment reversal = service().reversePayment(payment.getId(), "Cheque bounced", actorId);

        assertThat(reversal.getType()).isEqualTo(PaymentType.REVERSAL);
        assertThat(reversal.getReversesPaymentId()).isEqualTo(payment.getId());
        assertThat(reversal.getReason()).isEqualTo("Cheque bounced");
        assertThat(inv.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.PENDING);
    }
}
