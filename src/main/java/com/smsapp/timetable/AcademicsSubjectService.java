package com.smsapp.timetable;

import com.smsapp.academics.ClassSubjectRepository;
import com.smsapp.academics.Subject;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.School;
import com.smsapp.school.SchoolRepository;
import com.smsapp.timetable.TimetableDtos.SubjectRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Academics > Subjects: the subjects of the school with their code and type. Names are unique (as before) and codes
 * are unique ignoring case. A subject that a class, a subject group or the timetable uses cannot be deleted.
 */
@Service
public class AcademicsSubjectService {

    private final SubjectRepository subjectRepository;
    private final ClassSubjectRepository classSubjectRepository;
    private final SubjectGroupRepository groupRepository;
    private final TimetableEntryRepository timetableRepository;
    private final SchoolRepository schoolRepository;
    private final AuditService auditService;

    public AcademicsSubjectService(SubjectRepository subjectRepository, ClassSubjectRepository classSubjectRepository,
                                   SubjectGroupRepository groupRepository, TimetableEntryRepository timetableRepository,
                                   SchoolRepository schoolRepository, AuditService auditService) {
        this.subjectRepository = subjectRepository;
        this.classSubjectRepository = classSubjectRepository;
        this.groupRepository = groupRepository;
        this.timetableRepository = timetableRepository;
        this.schoolRepository = schoolRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Subject> list() {
        return subjectRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if the name or code is already used, 404 if the school has not been set up. */
    @Transactional
    public Subject create(SubjectRequest request) {
        School school = schoolRepository.findAllByOrderByName().stream().findFirst()
                .orElseThrow(() -> new ApiException("School not found", HttpStatus.NOT_FOUND));
        String name = request.name().trim();
        String code = blankToNull(request.code());
        if (subjectRepository.existsBySchoolIdAndName(school.getId(), name)) {
            throw nameTaken(name);
        }
        requireCodeFree(code, null);
        Subject subject = new Subject();
        subject.setSchoolId(school.getId());
        apply(subject, name, code, request.type());
        Subject saved = save(subject, name, code);
        auditService.log(AuditActions.SUBJECT_CREATED, AuditActions.SUBJECT, saved.getId(), Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such subject, 409 if another subject has that name or code. */
    @Transactional
    public Subject update(UUID id, SubjectRequest request) {
        Subject subject = require(id);
        String name = request.name().trim();
        String code = blankToNull(request.code());
        boolean nameChanged = !subject.getName().equals(name);
        if (nameChanged && subjectRepository.existsBySchoolIdAndName(subject.getSchoolId(), name)) {
            throw nameTaken(name);
        }
        requireCodeFree(code, id);
        apply(subject, name, code, request.type());
        Subject saved = save(subject, name, code);
        auditService.log(AuditActions.SUBJECT_UPDATED, AuditActions.SUBJECT, id, Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such subject, 409 if a class, subject group or timetable period uses it. */
    @Transactional
    public void delete(UUID id) {
        Subject subject = require(id);
        if (classSubjectRepository.findAll().stream().anyMatch(cs -> cs.getSubjectId().equals(id))
                || groupRepository.existsBySubject(id) || timetableRepository.existsBySubjectId(id)) {
            throw new ApiException("'" + subject.getName() + "' is used by a class, subject group or timetable and can't be deleted",
                    HttpStatus.CONFLICT);
        }
        subjectRepository.delete(subject);
        auditService.log(AuditActions.SUBJECT_DELETED, AuditActions.SUBJECT, id, Map.of("name", subject.getName()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private Subject require(UUID id) {
        return subjectRepository.findById(id).orElseThrow(() -> new ApiException("Subject not found", HttpStatus.NOT_FOUND));
    }

    private void requireCodeFree(String code, UUID selfId) {
        if (code != null && subjectRepository.findAll().stream()
                .anyMatch(s -> code.equalsIgnoreCase(s.getCode()) && !s.getId().equals(selfId))) {
            throw new ApiException("A subject with code '" + code + "' already exists", HttpStatus.CONFLICT);
        }
    }

    private Subject save(Subject subject, String name, String code) {
        try {
            return subjectRepository.saveAndFlush(subject);
        } catch (DataIntegrityViolationException ex) {
            throw code != null ? new ApiException("A subject with code '" + code + "' already exists", HttpStatus.CONFLICT) : nameTaken(name);
        }
    }

    private static void apply(Subject subject, String name, String code, String type) {
        subject.setName(name);
        subject.setCode(code);
        subject.setSubjectType(type.trim().toUpperCase(Locale.ROOT));
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A subject named '" + name + "' already exists", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
