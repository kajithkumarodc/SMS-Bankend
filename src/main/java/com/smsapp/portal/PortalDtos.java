package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.fee.Invoice;
import com.smsapp.student.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Curated self-service views for the student and parent portals. Internal link ids are never exposed. */
final class PortalDtos {

    private PortalDtos() {
    }

    record StudentView(
            UUID id,
            String fullName,
            String admissionNumber,
            LocalDate dateOfBirth,
            String guardianName,
            String guardianContact,
            String status,
            UUID sectionId) {

        static StudentView from(Student student) {
            return new StudentView(
                    student.getId(),
                    student.getFullName(),
                    student.getAdmissionNumber(),
                    student.getDateOfBirth(),
                    student.getGuardianName(),
                    student.getGuardianContact(),
                    student.getStatus(),
                    student.getSectionId());
        }
    }

    record AttendanceEntryView(LocalDate date, String status) {

        static AttendanceEntryView from(AttendanceRecord entry) {
            return new AttendanceEntryView(entry.getDate(), entry.getStatus());
        }
    }

    /**
     * One invoice row for the parent portal. Carries the Razorpay order id (so the
     * portal can resume a checkout) but never any raw payment data (plan section 7.2a).
     */
    record InvoiceView(
            UUID id,
            UUID studentId,
            UUID feeStructureId,
            BigDecimal amount,
            String status,
            String razorpayOrderId,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt) {

        static InvoiceView from(Invoice invoice) {
            return new InvoiceView(invoice.getId(), invoice.getStudentId(), invoice.getFeeStructureId(),
                    invoice.getAmount(), invoice.getStatus(), invoice.getRazorpayOrderId(),
                    invoice.getCreatedAt(), invoice.getPaidAt());
        }
    }

    /** One exam result row for the portal. Same shape as the staff endpoint's response. */
    record ExamResultView(
            UUID examId,
            String examName,
            LocalDate examDate,
            UUID subjectId,
            BigDecimal maxMarks,
            BigDecimal marksObtained) {

        static ExamResultView from(StudentExamResult result) {
            return new ExamResultView(result.getExamId(), result.getExamName(), result.getExamDate(),
                    result.getSubjectId(), result.getMaxMarks(), result.getMarksObtained());
        }
    }
}
