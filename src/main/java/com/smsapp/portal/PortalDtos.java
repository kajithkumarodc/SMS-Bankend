package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.student.Student;

import java.time.LocalDate;
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
}
