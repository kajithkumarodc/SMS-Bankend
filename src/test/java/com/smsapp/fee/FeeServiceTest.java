package com.smsapp.fee;

import com.smsapp.academics.ClassRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.fee.FeeDtos.FeeStructureItemResponse;
import com.smsapp.fee.FeeDtos.LineItemRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeeServiceTest {

    @Mock
    private FeeStructureRepository feeStructureRepository;

    @Mock
    private FeeStructureItemRepository feeStructureItemRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private com.smsapp.school.SchoolRepository schoolRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private FeeTypeRepository feeTypeRepository;

    @Mock
    private FeePaymentRepository feePaymentRepository;

    @Mock
    private RazorpayGateway razorpayGateway;

    @Mock
    private FeePaymentRecorder paymentRecorder;

    @Mock
    private AuditService auditService;

    private final RazorpayProperties properties = new RazorpayProperties("rzp_test_key", "secret", "whsec", "INR");

    private final UUID schoolId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID feeStructureId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();

    private FeeService service() {
        return new FeeService(feeStructureRepository, feeStructureItemRepository, invoiceRepository,
                schoolRepository, classRepository, studentRepository, feeTypeRepository, feePaymentRepository,
                razorpayGateway, properties, paymentRecorder, auditService);
    }

    private FeeStructure structure(String amount) {
        FeeStructure s = new FeeStructure();
        s.setId(feeStructureId);
        s.setSchoolId(schoolId);
        s.setName("Term 1 Tuition");
        s.setAmount(new BigDecimal(amount));
        s.setDueDate(LocalDate.of(2026, 6, 1));
        return s;
    }

    private Invoice invoice(String status) {
        Invoice i = new Invoice();
        i.setId(invoiceId);
        i.setStudentId(studentId);
        i.setFeeStructureId(feeStructureId);
        i.setAmount(new BigDecimal("5000.00"));
        i.setNetAmount(new BigDecimal("5000.00"));
        i.setStatus(status);
        if (InvoiceStatus.PAID.equals(status)) {
            i.setPaidAmount(new BigDecimal("5000.00"));
        }
        return i;
    }

    // --- Fee structures ----------------------------------------------

    @Test
    void createsFeeStructure() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(feeStructureRepository.save(any(FeeStructure.class))).thenAnswer(inv -> inv.getArgument(0));

        FeeStructure created = service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, null, "2026-2027", "Term 1 Tuition", new BigDecimal("5000.00"),
                LocalDate.of(2026, 6, 1), null, null, null));

        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getClassId()).isNull();
        assertThat(created.getAcademicYear()).isEqualTo("2026-2027");
        assertThat(created.getName()).isEqualTo("Term 1 Tuition");
        assertThat(created.getAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void rejectsFeeStructureForNonexistentSchoolWith404() {
        when(schoolRepository.existsById(schoolId)).thenReturn(false);

        assertThatThrownBy(() -> service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, null, "2026-2027", "X", new BigDecimal("10.00"), LocalDate.of(2026, 6, 1), null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(feeStructureRepository, never()).save(any());
    }

    @Test
    void rejectsFeeStructureForNonexistentClassWith404() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(classRepository.existsById(classId)).thenReturn(false);

        assertThatThrownBy(() -> service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, classId, "2026-2027", "Grade 5 fees", new BigDecimal("100.00"),
                LocalDate.of(2026, 6, 1), null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(feeStructureRepository, never()).save(any());
    }

    @Test
    void rejectsFeeStructureWithNeitherAmountNorItemsWith400() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);

        assertThatThrownBy(() -> service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, null, "2026-2027", "X", null, LocalDate.of(2026, 6, 1), null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(feeStructureRepository, never()).save(any());
    }

    @Test
    void rejectsFeeStructureWithAnUnrecognizedCategoryWith400() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        List<LineItemRequest> items = List.of(new LineItemRequest("NOT_A_CATEGORY", null, null, new BigDecimal("100.00")));

        assertThatThrownBy(() -> service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, null, "2026-2027", "X", null, LocalDate.of(2026, 6, 1), null, null, items)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(feeStructureRepository, never()).save(any());
    }

    /** Pre-KG & LKG row from a real CBSE school's fee-structure sheet: 100+1500+13500+5500+4400+4400+4400 = 33800. */
    @Test
    void createsAClassSpecificFeeStructureWithMultipleLineItemsAndComputesTheTotal() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(classRepository.existsById(classId)).thenReturn(true);
        when(feeStructureRepository.save(any(FeeStructure.class))).thenAnswer(inv -> {
            FeeStructure s = inv.getArgument(0);
            s.setId(feeStructureId);
            return s;
        });
        List<LineItemRequest> items = List.of(
                new LineItemRequest("APPLICATION", null, null, new BigDecimal("100.00")),
                new LineItemRequest("ADMISSION", null, null, new BigDecimal("1500.00")),
                new LineItemRequest("TERM_1", "Initial Fees", null, new BigDecimal("13500.00")),
                new LineItemRequest("TERM_1", "Books & Notes", null, new BigDecimal("5500.00")),
                new LineItemRequest("TERM_2", null, null, new BigDecimal("4400.00")),
                new LineItemRequest("TERM_3", null, null, new BigDecimal("4400.00")),
                new LineItemRequest("TERM_4", null, null, new BigDecimal("4400.00")));

        FeeStructure created = service().createFeeStructure(new CreateFeeStructureRequest(
                schoolId, classId, "2026-2027", "Pre-KG & LKG", null, LocalDate.of(2026, 6, 1), null, null, items));

        assertThat(created.getClassId()).isEqualTo(classId);
        assertThat(created.getAmount()).isEqualByComparingTo("33800.00");

        ArgumentCaptor<List<FeeStructureItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(feeStructureItemRepository).saveAll(captor.capture());
        List<FeeStructureItem> saved = captor.getValue();
        assertThat(saved).hasSize(7);
        assertThat(saved.get(0).getCategory()).isEqualTo("APPLICATION");
        assertThat(saved.get(0).getSequenceOrder()).isEqualTo(0);
        assertThat(saved.get(2).getLabel()).isEqualTo("Initial Fees");
        assertThat(saved.get(3).getLabel()).isEqualTo("Books & Notes");
        assertThat(saved.get(6).getSequenceOrder()).isEqualTo(6);
    }

    @Test
    void listFeeStructuresPassesTheClassAndAcademicYearFilterThrough() {
        when(feeStructureRepository.search(classId, "2026-2027")).thenReturn(List.of());

        service().listFeeStructures(classId, "2026-2027");

        verify(feeStructureRepository).search(classId, "2026-2027");
    }

    @Test
    void itemResponsesForReturnsPersistedItemsInSequenceOrder() {
        FeeStructure s = structure("100.00");
        FeeStructureItem item = new FeeStructureItem();
        item.setCategory("APPLICATION");
        when(feeStructureItemRepository.findByFeeStructureIdOrderBySequenceOrder(feeStructureId))
                .thenReturn(List.of(item));

        List<FeeStructureItemResponse> items = service().itemResponsesFor(s);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).category()).isEqualTo("APPLICATION");
    }

    @Test
    void itemResponsesForSynthesizesAnImplicitItemForABackwardCompatibleFlatStructure() {
        FeeStructure s = structure("5000.00");
        when(feeStructureItemRepository.findByFeeStructureIdOrderBySequenceOrder(feeStructureId))
                .thenReturn(List.of());

        List<FeeStructureItemResponse> items = service().itemResponsesFor(s);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).category()).isEqualTo(FeeStructureItemCategory.OTHER);
        assertThat(items.get(0).amount()).isEqualByComparingTo("5000.00");
    }

    // --- Invoices --------------------------------------------------

    @Test
    void createsInvoiceCopyingTheAmountFromTheFeeStructure() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findById(feeStructureId)).thenReturn(Optional.of(structure("5000.00")));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        Invoice created = service().createInvoice(new CreateInvoiceRequest(studentId, feeStructureId));

        assertThat(created.getStudentId()).isEqualTo(studentId);
        assertThat(created.getFeeStructureId()).isEqualTo(feeStructureId);
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(created.getAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void rejectsInvoiceForNonexistentStudentWith404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createInvoice(new CreateInvoiceRequest(studentId, feeStructureId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void rejectsInvoiceForNonexistentFeeStructureWith404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findById(feeStructureId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createInvoice(new CreateInvoiceRequest(studentId, feeStructureId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void listInvoicesRejectsANonexistentStudentWith404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listInvoicesForStudent(studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Checkout -------------------------------------------------

    @Test
    void checkoutCreatesARazorpayOrderAndReturnsOnlySafeFields() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(razorpayGateway.createOrder(anyLong(), eq("INR"), anyString(), anyMap())).thenReturn("order_TEST123");
        when(razorpayGateway.keyId()).thenReturn("rzp_test_key");

        CheckoutResponse response = service().startCheckout(invoiceId, null);

        assertThat(response.razorpayOrderId()).isEqualTo("order_TEST123");
        assertThat(response.razorpayKeyId()).isEqualTo("rzp_test_key");
        assertThat(response.amountInPaise()).isEqualTo(500000L); // 5000.00 -> paise
        assertThat(response.currency()).isEqualTo("INR");
    }

    @Test
    void checkoutRejectsANonexistentInvoiceWith404() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().startCheckout(invoiceId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutRejectsAnAlreadyPaidInvoiceWith409() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice(InvoiceStatus.PAID)));

        assertThatThrownBy(() -> service().startCheckout(invoiceId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutByAParentRejectsAnInvoiceThatIsNotTheirChildsWith404() {
        UUID guardianUserId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().startCheckout(invoiceId, guardianUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutByAParentIsAllowedForTheirOwnChildsInvoice() {
        UUID guardianUserId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId))
                .thenReturn(Optional.of(new com.smsapp.student.Student()));
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_P1");
        when(razorpayGateway.keyId()).thenReturn("rzp_test_key");

        CheckoutResponse response = service().startCheckout(invoiceId, guardianUserId);

        assertThat(response.razorpayOrderId()).isEqualTo("order_P1");
    }

    // --- Webhook application (idempotency + guards) ----------------

    @Test
    void markInvoicePaidFlipsAPendingInvoiceToPaid() {
        Invoice pending = invoice(InvoiceStatus.PENDING);
        pending.setRazorpayOrderId("order_1");
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(pending));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        service().markInvoicePaid(invoiceId, "order_1", "pay_1");

        assertThat(pending.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(pending.getRazorpayPaymentId()).isEqualTo("pay_1");
        assertThat(pending.getPaidAt()).isNotNull();
        verify(auditService).logAs(any(), anyString(), anyString(), eq(invoiceId), anyMap());
    }

    @Test
    void markInvoicePaidIsIdempotentForAnAlreadyPaidInvoice() {
        Invoice paid = invoice(InvoiceStatus.PAID);
        paid.setRazorpayOrderId("order_1");
        paid.setRazorpayPaymentId("pay_original");
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(paid));

        service().markInvoicePaid(invoiceId, "order_1", "pay_duplicate");

        assertThat(paid.getRazorpayPaymentId()).isEqualTo("pay_original");
        verify(invoiceRepository, never()).save(any());
        verify(auditService, never()).logAs(any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void markInvoicePaidIgnoresAnOrderIdThatDoesNotMatchTheInvoice() {
        Invoice pending = invoice(InvoiceStatus.PENDING);
        pending.setRazorpayOrderId("order_real");
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(pending));

        service().markInvoicePaid(invoiceId, "order_forged", "pay_x");

        assertThat(pending.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void markInvoicePaidIsANoOpForAnUnknownInvoice() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());
        when(invoiceRepository.findByRazorpayOrderId("order_1")).thenReturn(Optional.empty());

        service().markInvoicePaid(invoiceId, "order_1", "pay_1");

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void checkoutAndWebhookAgreeOnTheOrderNoteKeys() {
        assertThat(FeeService.NOTE_INVOICE_ID).isEqualTo("invoiceId");
    }

    @Test
    void createInvoiceAuditsWithTheNewInvoiceId() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findById(feeStructureId)).thenReturn(Optional.of(structure("5000.00")));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            i.setId(invoiceId);
            return i;
        });

        service().createInvoice(new CreateInvoiceRequest(studentId, feeStructureId));

        verify(auditService).log(anyString(), anyString(), eq(invoiceId), anyMap());
    }
}
