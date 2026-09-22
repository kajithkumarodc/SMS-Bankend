package com.smsapp.student;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentDocumentServiceTest {

    @Mock
    private StudentDocumentRepository documentRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private final UUID studentId = UUID.randomUUID();

    private StudentDocumentService service() {
        return new StudentDocumentService(documentRepository, studentRepository, auditService,
                System.getProperty("java.io.tmpdir") + "/sms-doc-service-test");
    }

    @Test
    void rejectsUploadForANonexistentStudentWith404() {
        when(studentRepository.existsById(studentId)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "data".getBytes());

        assertThatThrownBy(() -> service().upload(studentId, "BIRTH_CERTIFICATE", file, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsAnEmptyFileWith400() {
        when(studentRepository.existsById(studentId)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service().upload(studentId, "BIRTH_CERTIFICATE", file, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnUnsupportedContentTypeWith400() {
        when(studentRepository.existsById(studentId)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/x-msdownload", "data".getBytes());

        assertThatThrownBy(() -> service().upload(studentId, "OTHER", file, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAFileOverTheSizeLimitWith400() {
        when(studentRepository.existsById(studentId)).thenReturn(true);
        byte[] tooBig = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooBig);

        assertThatThrownBy(() -> service().upload(studentId, "OTHER", file, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsABlankDocumentTypeWith400() {
        when(studentRepository.existsById(studentId)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "cert.pdf", "application/pdf", "data".getBytes());

        assertThatThrownBy(() -> service().upload(studentId, "  ", file, null, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void storesAValidUploadAndGeneratesASafeStoredFilenameNeverTheOriginal() {
        when(studentRepository.existsById(studentId)).thenReturn(true);
        when(documentRepository.save(org.mockito.ArgumentMatchers.any(StudentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd.pdf", "application/pdf", "data".getBytes());

        StudentDocument saved = service().upload(studentId, "OTHER", file, "  some notes  ", null);

        assertThat(saved.getStoredFilename()).endsWith(".pdf");
        assertThat(saved.getStoredFilename()).isNotEqualTo(file.getOriginalFilename());
        assertThat(saved.getStoredFilename()).doesNotContain("/", "\\");
        assertThat(saved.getOriginalFilename()).isEqualTo("passwd.pdf");
        assertThat(saved.getNotes()).isEqualTo("some notes");
        assertThat(saved.getFileSizeBytes()).isEqualTo(4);
    }

    @Test
    void getRejectsADocumentNotBelongingToTheStudentWith404() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndStudentId(documentId, studentId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().get(studentId, documentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
