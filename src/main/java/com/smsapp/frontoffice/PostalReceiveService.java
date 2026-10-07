package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.DocumentNumberGenerator.IssuedNumber;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import com.smsapp.frontoffice.PostalReceiveDtos.ReceiveRequest;
import com.smsapp.frontoffice.PostalReceiveDtos.ReceiveResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Front Office / Postal Receive. Reference numbers come from {@link DocumentNumberGenerator} ({@code PRC}
 * series); supporting documents are stored with {@link FrontOfficeFileStore}. File deletions wait for the
 * database commit, and a file written for a save that then rolls back is removed, so disk and database never
 * disagree.
 */
@Service
public class PostalReceiveService {

    static final String REFERENCE_SERIES = "PRC";
    static final String STORAGE_AREA = "postal-receives";
    static final int MAX_DOCUMENTS = 10;

    private final PostalReceiveRepository receiveRepository;
    private final PostalReceiveDocumentRepository documentRepository;
    private final DocumentNumberGenerator numberGenerator;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public PostalReceiveService(PostalReceiveRepository receiveRepository,
                                 PostalReceiveDocumentRepository documentRepository,
                                 DocumentNumberGenerator numberGenerator, FrontOfficeFileStore fileStore,
                                 AuditService auditService) {
        this.receiveRepository = receiveRepository;
        this.documentRepository = documentRepository;
        this.numberGenerator = numberGenerator;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** Issues the next PRC reference number and saves the received item in the same transaction. */
    @Transactional
    public PostalReceive create(ReceiveRequest request, UUID createdByUserId) {
        IssuedNumber number = numberGenerator.next(REFERENCE_SERIES);
        PostalReceive item = new PostalReceive();
        item.setReferenceNo(number.value());
        item.setAcademicYearId(number.academicYearId());
        item.setCreatedByUserId(createdByUserId);
        apply(item, request);
        PostalReceive saved = receiveRepository.save(item);
        // Audit after commit: AuditService writes in its own transaction (a second connection), and doing that
        // while this transaction still holds the number counter's row lock lets concurrent saves exhaust the
        // connection pool waiting on each other. After commit the lock is released, and only saves that
        // actually committed get logged.
        UUID id = saved.getId();
        String referenceNo = saved.getReferenceNo();
        String fromTitle = saved.getFromTitle();
        afterCommit(() -> auditService.log(AuditActions.POSTAL_RECEIVE_CREATED, AuditActions.POSTAL_RECEIVE, id,
                Map.of("referenceNo", referenceNo, "fromTitle", fromTitle)));
        return saved;
    }

    /** Edits the form fields; the reference number is never changed. @throws ApiException 404 if no such received item. */
    @Transactional
    public PostalReceive update(UUID id, ReceiveRequest request) {
        PostalReceive item = require(id);
        apply(item, request);
        PostalReceive saved = receiveRepository.save(item);
        auditService.log(AuditActions.POSTAL_RECEIVE_UPDATED, AuditActions.POSTAL_RECEIVE, id,
                Map.of("referenceNo", saved.getReferenceNo()));
        return saved;
    }

    /** Deletes the received item, its document rows, and (after commit) their files. @throws ApiException 404. */
    @Transactional
    public void delete(UUID id) {
        PostalReceive item = require(id);
        List<PostalReceiveDocument> documents = documentRepository.findByReceiveIdOrderByUploadedAt(id);
        documentRepository.deleteAll(documents);
        receiveRepository.delete(item);
        afterCommit(() -> {
            documents.forEach(d -> fileStore.delete(STORAGE_AREA, id, d.getStoredFilename()));
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.POSTAL_RECEIVE_DELETED, AuditActions.POSTAL_RECEIVE, id,
                Map.of("referenceNo", item.getReferenceNo(), "documents", documents.size()));
    }

    @Transactional(readOnly = true)
    public PostalReceive get(UUID id) {
        return require(id);
    }

    /** Search, paginated. {@code query} matches reference no, to/from title, address or note. */
    @Transactional(readOnly = true)
    public Page<PostalReceive> search(String query, Pageable pageable) {
        Specification<PostalReceive> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("referenceNo")), like),
                        cb.like(cb.lower(root.get("toTitle")), like),
                        cb.like(cb.lower(root.get("fromTitle")), like),
                        cb.like(cb.lower(root.get("address")), like),
                        cb.like(cb.lower(root.get("note")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return receiveRepository.findAll(spec, pageable);
    }

    // --- Documents ------------------------------------------------------------------

    /**
     * Attaches one supporting document.
     *
     * @throws ApiException 404 if no such received item, 400 if the file is invalid or the received item already has
     *         {@value #MAX_DOCUMENTS} documents.
     */
    @Transactional
    public PostalReceiveDocument addDocument(UUID receiveId, MultipartFile file, UUID uploadedByUserId) {
        PostalReceive item = require(receiveId);
        if (documentRepository.countByReceiveId(receiveId) >= MAX_DOCUMENTS) {
            throw new ApiException("A received item can have at most " + MAX_DOCUMENTS + " documents", HttpStatus.BAD_REQUEST);
        }
        StoredFile stored = fileStore.store(STORAGE_AREA, receiveId, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, receiveId, stored.storedFilename()));

        PostalReceiveDocument document = new PostalReceiveDocument();
        document.setReceiveId(receiveId);
        document.setOriginalFilename(stored.originalFilename());
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.sizeBytes());
        document.setUploadedByUserId(uploadedByUserId);
        PostalReceiveDocument saved = documentRepository.save(document);
        auditService.log(AuditActions.POSTAL_RECEIVE_DOCUMENT_UPLOADED, AuditActions.POSTAL_RECEIVE, receiveId,
                Map.of("referenceNo", item.getReferenceNo(), "fileName", saved.getOriginalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if the document doesn't belong to this received item. */
    @Transactional(readOnly = true)
    public PostalReceiveDocument getDocument(UUID receiveId, UUID documentId) {
        return documentRepository.findByIdAndReceiveId(documentId, receiveId)
                .orElseThrow(() -> new ApiException("Document not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Resource loadDocument(PostalReceiveDocument document) {
        return fileStore.load(STORAGE_AREA, document.getReceiveId(), document.getStoredFilename());
    }

    /** @throws ApiException 404 if the document doesn't belong to this received item. */
    @Transactional
    public void deleteDocument(UUID receiveId, UUID documentId) {
        PostalReceiveDocument document = getDocument(receiveId, documentId);
        documentRepository.delete(document);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, receiveId, document.getStoredFilename()));
        auditService.log(AuditActions.POSTAL_RECEIVE_DOCUMENT_DELETED, AuditActions.POSTAL_RECEIVE, receiveId,
                Map.of("fileName", document.getOriginalFilename()));
    }

    // --- Response mapping -------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReceiveResponse toResponse(PostalReceive item) {
        return ReceiveResponse.from(item, documentRepository.findByReceiveIdOrderByUploadedAt(item.getId()));
    }

    /** Maps a page with one query for all of its documents. */
    @Transactional(readOnly = true)
    public List<ReceiveResponse> toResponses(List<PostalReceive> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PostalReceiveDocument>> byReceive = documentRepository
                .findByReceiveIdInOrderByUploadedAt(items.stream().map(PostalReceive::getId).toList())
                .stream().collect(Collectors.groupingBy(PostalReceiveDocument::getReceiveId));
        return items.stream()
                .map(d -> ReceiveResponse.from(d, byReceive.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    // --- Helpers ------------------------------------------------------------------------

    private static void apply(PostalReceive item, ReceiveRequest request) {
        item.setFromTitle(request.fromTitle().trim());
        item.setToTitle(blankToNull(request.toTitle()));
        item.setAddress(blankToNull(request.address()));
        item.setNote(blankToNull(request.note()));
        item.setReceiveDate(request.receiveDate());
    }

    private PostalReceive require(UUID id) {
        return receiveRepository.findById(id)
                .orElseThrow(() -> new ApiException("Postal receive not found", HttpStatus.NOT_FOUND));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void onRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
