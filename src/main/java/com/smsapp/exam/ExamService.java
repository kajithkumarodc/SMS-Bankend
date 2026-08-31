package com.smsapp.exam;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamDtos.CreateExamRequest;
import com.smsapp.exam.ExamDtos.RecordMarkRequest;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.student.StudentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExamService {

    private final ExamRepository examRepository;
    private final ExamMarkRepository examMarkRepository;
    private final ClassRepository classRepository;
    private final SubjectRepository subjectRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public ExamService(ExamRepository examRepository, ExamMarkRepository examMarkRepository,
                       ClassRepository classRepository, SubjectRepository subjectRepository,
                       StudentRepository studentRepository, AuditService auditService) {
        this.examRepository = examRepository;
        this.examMarkRepository = examMarkRepository;
        this.classRepository = classRepository;
        this.subjectRepository = subjectRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    /** {@code created} is true when a new mark row was inserted, false when an existing one was corrected. */
    public record MarkResult(ExamMark mark, boolean created) {
    }

    /**
     * @throws ApiException 404 if the class or the subject is not in the caller's
     *         tenant, 400 if {@code maxMarks} is not positive.
     */
    @Transactional
    public Exam createExam(UUID tenantId, CreateExamRequest request) {
        if (classRepository.findByIdAndTenantId(request.classId(), tenantId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        if (subjectRepository.findByIdAndTenantId(request.subjectId(), tenantId).isEmpty()) {
            throw new ApiException("Subject not found", HttpStatus.NOT_FOUND);
        }
        if (request.maxMarks().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Max marks must be greater than zero", HttpStatus.BAD_REQUEST);
        }

        Exam exam = new Exam();
        exam.setTenantId(tenantId);
        exam.setClassId(request.classId());
        exam.setSubjectId(request.subjectId());
        exam.setName(request.name().trim());
        exam.setExamDate(request.examDate());
        exam.setMaxMarks(request.maxMarks());
        Exam saved = examRepository.save(exam);

        auditService.log(AuditActions.EXAM_CREATED, AuditActions.EXAM, saved.getId(),
                Map.of("name", saved.getName(),
                        "classId", saved.getClassId().toString(),
                        "subjectId", saved.getSubjectId().toString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if the class is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<Exam> listForClass(UUID tenantId, UUID classId) {
        if (classRepository.findByIdAndTenantId(classId, tenantId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        return examRepository.findByTenantIdAndClassIdOrderByExamDateDesc(tenantId, classId);
    }

    /**
     * Records (or corrects) one student's marks for an exam. Re-recording updates the
     * existing row rather than failing -- a teacher fixing a mistake (plan section 2).
     *
     * @throws ApiException 404 if the exam or the student is not in the caller's tenant,
     *         400 if {@code marksObtained} is outside {@code [0, exam.maxMarks]}.
     */
    @Transactional
    public MarkResult recordMark(UUID tenantId, UUID examId, RecordMarkRequest request) {
        Exam exam = examRepository.findByIdAndTenantId(examId, tenantId)
                .orElseThrow(() -> new ApiException("Exam not found", HttpStatus.NOT_FOUND));
        if (studentRepository.findByIdAndTenantId(request.studentId(), tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        BigDecimal marks = request.marksObtained();
        if (marks.compareTo(BigDecimal.ZERO) < 0 || marks.compareTo(exam.getMaxMarks()) > 0) {
            throw new ApiException("Marks must be between 0 and " + exam.getMaxMarks().toPlainString(),
                    HttpStatus.BAD_REQUEST);
        }

        ExamMark mark = examMarkRepository
                .findByTenantIdAndExamIdAndStudentId(tenantId, examId, request.studentId())
                .orElse(null);
        boolean created = mark == null;
        if (created) {
            mark = new ExamMark();
            mark.setTenantId(tenantId);
            mark.setExamId(examId);
            mark.setStudentId(request.studentId());
        }
        mark.setMarksObtained(marks);
        ExamMark saved = examMarkRepository.save(mark);

        auditService.log(created ? AuditActions.EXAM_MARK_RECORDED : AuditActions.EXAM_MARK_CHANGED,
                AuditActions.EXAM_MARK, saved.getId(),
                Map.of("examId", examId.toString(),
                        "studentId", request.studentId().toString(),
                        "marksObtained", marks.toPlainString()));
        return new MarkResult(saved, created);
    }

    /**
     * @throws ApiException 404 if the exam is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<ExamMark> gradebook(UUID tenantId, UUID examId) {
        if (examRepository.findByIdAndTenantId(examId, tenantId).isEmpty()) {
            throw new ApiException("Exam not found", HttpStatus.NOT_FOUND);
        }
        return examMarkRepository.findByTenantIdAndExamIdOrderByCreatedAt(tenantId, examId);
    }

    /**
     * @throws ApiException 404 if the student is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<StudentExamResult> studentResults(UUID tenantId, UUID studentId) {
        if (studentRepository.findByIdAndTenantId(studentId, tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        return examMarkRepository.findResultsForStudent(tenantId, studentId);
    }
}
