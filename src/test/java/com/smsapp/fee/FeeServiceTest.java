package com.smsapp.fee;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeeServiceTest {

    @Mock
    private FeeStructureRepository feeStructureRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private com.smsapp.school.SchoolRepository schoolRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private RazorpayGateway razorpayGateway;

    @Mock
    private AuditService auditService;

    @Mock
    private EntityManager entityManager;

    private final RazorpayProperties properties = new RazorpayProperties("rzp_test_key", "secret", "whsec", "INR");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID schoolId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID feeStructureId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();

    private FeeService service() {
        return new FeeService(feeStructureRepository, invoiceRepository, schoolRepository, studentRepository,
                razorpayGateway, properties, auditService, entityManager);
    }

    @BeforeEach
    void stubTenantSessionQuery() {
        Query query = mock(Query.class, RETURNS_SELF);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.getSingleResult()).thenReturn("");
    }

    private FeeStructure structure(String amount) {
        FeeStructure s = new FeeStructure();
        s.setId(feeStructureId);
        s.setTenantId(tenantId);
        s.setSchoolId(schoolId);
        s.setName("Term 1 Tuition");
        s.setAmount(new BigDecimal(amount));
        s.setDueDate(LocalDate.of(2026, 6, 1));
        return s;
    }

    private Invoice invoice(String status) {
        Invoice i = new Invoice();
        i.setId(invoiceId);
        i.setTenantId(tenantId);
        i.setStudentId(studentId);
        i.setFeeStructureId(feeStructureId);
        i.setAmount(new BigDecimal("5000.00"));
        i.setStatus(status);
        return i;
    }

    // --- Fee structures ----------------------------------------------

    @Test
    void createsFeeStructureScopedToTenant() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(feeStructureRepository.save(any(FeeStructure.class))).thenAnswer(inv -> inv.getArgument(0));

        FeeStructure created = service().createFeeStructure(tenantId, new CreateFeeStructureRequest(
                schoolId, "Term 1 Tuition", new BigDecimal("5000.00"), LocalDate.of(2026, 6, 1)));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getName()).isEqualTo("Term 1 Tuition");
        assertThat(created.getAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void rejectsFeeStructureForSchoolNotInTenantWith404() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().createFeeStructure(tenantId, new CreateFeeStructureRequest(
                schoolId, "X", new BigDecimal("10.00"), LocalDate.of(2026, 6, 1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(feeStructureRepository, never()).save(any());
    }

    // --- Invoices --------------------------------------------------

    @Test
    void createsInvoiceCopyingTheAmountFromTheFeeStructure() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findByIdAndTenantId(feeStructureId, tenantId))
                .thenReturn(Optional.of(structure("5000.00")));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        Invoice created = service().createInvoice(tenantId, new CreateInvoiceRequest(studentId, feeStructureId));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getStudentId()).isEqualTo(studentId);
        assertThat(created.getFeeStructureId()).isEqualTo(feeStructureId);
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(created.getAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void rejectsInvoiceForStudentNotInTenantWith404() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createInvoice(tenantId, new CreateInvoiceRequest(studentId, feeStructureId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void rejectsInvoiceForFeeStructureNotInTenantWith404() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findByIdAndTenantId(feeStructureId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createInvoice(tenantId, new CreateInvoiceRequest(studentId, feeStructureId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void listInvoicesRejectsAStudentNotInTenantWith404() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listInvoicesForStudent(tenantId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Checkout -------------------------------------------------

    @Test
    void checkoutCreatesARazorpayOrderAndReturnsOnlySafeFields() {
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(razorpayGateway.createOrder(anyLong(), eq("INR"), anyString(), anyMap())).thenReturn("order_TEST123");
        when(razorpayGateway.keyId()).thenReturn("rzp_test_key");

        CheckoutResponse response = service().startCheckout(tenantId, invoiceId, null);

        assertThat(response.razorpayOrderId()).isEqualTo("order_TEST123");
        assertThat(response.razorpayKeyId()).isEqualTo("rzp_test_key");
        assertThat(response.amountInPaise()).isEqualTo(500000L); // 5000.00 -> paise
        assertThat(response.currency()).isEqualTo("INR");
    }

    @Test
    void checkoutRejectsAnInvoiceNotInTenantWith404() {
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().startCheckout(tenantId, invoiceId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutRejectsAnAlreadyPaidInvoiceWith409() {
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(invoice(InvoiceStatus.PAID)));

        assertThatThrownBy(() -> service().startCheckout(tenantId, invoiceId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutByAParentRejectsAnInvoiceThatIsNotTheirChildsWith404() {
        UUID guardianUserId = UUID.randomUUID();
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId))
                .thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().startCheckout(tenantId, invoiceId, guardianUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(razorpayGateway, never()).createOrder(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkoutByAParentIsAllowedForTheirOwnChildsInvoice() {
        UUID guardianUserId = UUID.randomUUID();
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId))
                .thenReturn(Optional.of(invoice(InvoiceStatus.PENDING)));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(new com.smsapp.student.Student()));
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_P1");
        when(razorpayGateway.keyId()).thenReturn("rzp_test_key");

        CheckoutResponse response = service().startCheckout(tenantId, invoiceId, guardianUserId);

        assertThat(response.razorpayOrderId()).isEqualTo("order_P1");
    }

    // --- Webhook application (idempotency + guards) ----------------

    @Test
    void markInvoicePaidFlipsAPendingInvoiceToPaid() {
        Invoice pending = invoice(InvoiceStatus.PENDING);
        pending.setRazorpayOrderId("order_1");
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(pending));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        service().markInvoicePaid(tenantId, invoiceId, "order_1", "pay_1");

        assertThat(pending.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(pending.getRazorpayPaymentId()).isEqualTo("pay_1");
        assertThat(pending.getPaidAt()).isNotNull();
        verify(auditService).logAs(eq(tenantId), any(), anyString(), anyString(), eq(invoiceId), anyMap());
    }

    @Test
    void markInvoicePaidIsIdempotentForAnAlreadyPaidInvoice() {
        Invoice paid = invoice(InvoiceStatus.PAID);
        paid.setRazorpayOrderId("order_1");
        paid.setRazorpayPaymentId("pay_original");
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(paid));

        service().markInvoicePaid(tenantId, invoiceId, "order_1", "pay_duplicate");

        assertThat(paid.getRazorpayPaymentId()).isEqualTo("pay_original");
        verify(invoiceRepository, never()).save(any());
        verify(auditService, never()).logAs(any(), any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void markInvoicePaidIgnoresAnOrderIdThatDoesNotMatchTheInvoice() {
        Invoice pending = invoice(InvoiceStatus.PENDING);
        pending.setRazorpayOrderId("order_real");
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(pending));

        service().markInvoicePaid(tenantId, invoiceId, "order_forged", "pay_x");

        assertThat(pending.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void markInvoicePaidIsANoOpForAnUnknownInvoice() {
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.empty());
        when(invoiceRepository.findByTenantIdAndRazorpayOrderId(tenantId, "order_1")).thenReturn(Optional.empty());

        service().markInvoicePaid(tenantId, invoiceId, "order_1", "pay_1");

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void checkoutAndWebhookAgreeOnTheOrderNoteKeys() {
        assertThat(FeeService.NOTE_TENANT_ID).isEqualTo("tenantId");
        assertThat(FeeService.NOTE_INVOICE_ID).isEqualTo("invoiceId");
    }

    @Test
    void createInvoiceAuditsWithTheNewInvoiceId() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(new Student()));
        when(feeStructureRepository.findByIdAndTenantId(feeStructureId, tenantId))
                .thenReturn(Optional.of(structure("5000.00")));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            i.setId(invoiceId);
            return i;
        });

        service().createInvoice(tenantId, new CreateInvoiceRequest(studentId, feeStructureId));

        verify(auditService).log(anyString(), anyString(), eq(invoiceId), anyMap());
    }
}
