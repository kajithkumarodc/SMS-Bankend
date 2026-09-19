package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.CreateSubjectRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final ClassSubjectRepository classSubjectRepository;
    private final ClassRepository classRepository;
    private final SchoolRepository schoolRepository;
    private final AuditService auditService;

    public SubjectService(SubjectRepository subjectRepository, ClassSubjectRepository classSubjectRepository,
                          ClassRepository classRepository, SchoolRepository schoolRepository,
                          AuditService auditService) {
        this.subjectRepository = subjectRepository;
        this.classSubjectRepository = classSubjectRepository;
        this.classRepository = classRepository;
        this.schoolRepository = schoolRepository;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 404 if the school does not exist, 409 if a subject with
     *         that name already exists for the school.
     */
    @Transactional
    public Subject create(CreateSubjectRequest request) {
        String name = request.name().trim();
        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (subjectRepository.existsBySchoolIdAndName(request.schoolId(), name)) {
            throw new ApiException("A subject named '" + name + "' already exists for this school",
                    HttpStatus.CONFLICT);
        }
        Subject subject = new Subject();
        subject.setSchoolId(request.schoolId());
        subject.setName(name);
        Subject saved = subjectRepository.save(subject);

        auditService.log(AuditActions.SUBJECT_CREATED, AuditActions.SUBJECT, saved.getId(),
                Map.of("name", saved.getName(), "schoolId", saved.getSchoolId().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Subject> list() {
        return subjectRepository.findAllByOrderByName();
    }

    /**
     * Assigns an existing subject to a class.
     *
     * @throws ApiException 404 if the class or the subject does not exist, 409 if
     *         the subject is already assigned to the class.
     */
    @Transactional
    public Subject assignToClass(UUID classId, UUID subjectId) {
        if (classRepository.findById(classId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ApiException("Subject not found", HttpStatus.NOT_FOUND));
        if (classSubjectRepository.existsByClassIdAndSubjectId(classId, subjectId)) {
            throw new ApiException("That subject is already assigned to this class", HttpStatus.CONFLICT);
        }
        ClassSubject link = new ClassSubject();
        link.setClassId(classId);
        link.setSubjectId(subjectId);
        ClassSubject saved = classSubjectRepository.save(link);

        auditService.log(AuditActions.CLASS_SUBJECT_ASSIGNED, AuditActions.CLASS_SUBJECT, saved.getId(),
                Map.of("classId", classId.toString(), "subjectId", subjectId.toString()));
        return subject;
    }

    /**
     * @throws ApiException 404 if the class does not exist.
     */
    @Transactional(readOnly = true)
    public List<Subject> listForClass(UUID classId) {
        if (classRepository.findById(classId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        return classSubjectRepository.findSubjectsForClass(classId);
    }
}
