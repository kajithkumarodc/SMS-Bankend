package com.smsapp.student;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Configurable identification documents (National ID, Local ID, Birth certificate, ...) for a student. */
@Service
public class StudentIdentificationService {

    private final StudentIdentificationRepository identificationRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public StudentIdentificationService(StudentIdentificationRepository identificationRepository,
                                        StudentRepository studentRepository, AuditService auditService) {
        this.identificationRepository = identificationRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    /** @throws ApiException 404 if the student doesn't exist. */
    @Transactional
    public StudentIdentification add(UUID studentId, String idType, String idValue, String notes) {
        if (!studentRepository.existsById(studentId)) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        StudentIdentification identification = new StudentIdentification();
        identification.setStudentId(studentId);
        identification.setIdType(idType.trim().toUpperCase(java.util.Locale.ROOT));
        identification.setIdValue(idValue.trim());
        identification.setNotes(notes != null && !notes.isBlank() ? notes.trim() : null);
        StudentIdentification saved = identificationRepository.save(identification);

        auditService.log(AuditActions.STUDENT_IDENTIFICATION_ADDED, AuditActions.STUDENT_IDENTIFICATION, saved.getId(),
                Map.of("studentId", studentId.toString(), "idType", saved.getIdType()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StudentIdentification> list(UUID studentId) {
        return identificationRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    /** @throws ApiException 404 if no such identification for this student. */
    @Transactional
    public void remove(UUID studentId, UUID identificationId) {
        StudentIdentification identification = identificationRepository.findById(identificationId)
                .filter(i -> i.getStudentId().equals(studentId))
                .orElseThrow(() -> new ApiException("Identification not found", HttpStatus.NOT_FOUND));
        identificationRepository.delete(identification);
        auditService.log(AuditActions.STUDENT_IDENTIFICATION_REMOVED, AuditActions.STUDENT_IDENTIFICATION, identificationId,
                Map.of("studentId", studentId.toString()));
    }
}
