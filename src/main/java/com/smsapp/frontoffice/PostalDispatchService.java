package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.DocumentNumberGenerator.IssuedNumber;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import com.smsapp.frontoffice.PostalDispatchDtos.DispatchRequest;
import com.smsapp.frontoffice.PostalDispatchDtos.DispatchResponse;
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
 * Front Office / Postal Dispatch. Reference numbers come from {@link DocumentNumberGenerator} ({@code DSP}
 * series); supporting documents are stored with {@link FrontOfficeFileStore}. File deletions wait for the
 * database commit, and a file written for a save that then rolls back is removed, so disk and database never
 * disagree.
 */
@Service
public class PostalDispatchService {

    static final String REFERENCE_SERIES = "DSP";
    static final String STORAGE_AREA = "postal-dispatches";
    static final int MAX_DOCUMENTS = 10;

    private final PostalDispatchRepository dispatchRepository;
    private final PostalDispatchDocumentRepository documentRepository;
    private final DocumentNumberGenerator numberGenerator;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public PostalDispatchService(PostalDispatchRepository dispatchRepository,
                                 PostalDispatchDocumentRepository documentRepository,
                                 DocumentNumberGenerator numberGenerator, FrontOfficeFileStore fileStore,
                                 AuditService auditService) {
        this.dispatchRepository = dispatchRepository;
        this.documentRepository = documentRepository;
        this.numberGenerator = numberGenerator;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** Issues the next DSP reference number and saves the dispatch in the same transaction. */
    @Transactional
    public PostalDispatch create(DispatchRequest request, UUID createdByUserId) {
        IssuedNumber number = numberGenerator.next(REFERENCE_SERIES);
        PostalDispatch dispatch = new PostalDispatch();
        dispatch.setReferenceNo(number.value());
        dispatch.setAcademicYearId(number.academicYearId());
        dispatch.setCreatedByUserId(createdByUserId);
        apply(dispatch, request);
        PostalDispatch saved = dispatchRepository.save(dispatch);
        // Audit after commit: AuditService writes in its own transaction (a second connection), and doing that
        // while this transaction still holds the number counter's row lock lets concurrent saves exhaust the
        // connection pool waiting on each other. After commit the lock is released, and only saves that
        // actually committed get logged.
        UUID id = saved.getId();
        String referenceNo = saved.getReferenceNo();
        String toTitle = saved.getToTitle();
        afterCommit(() -> auditService.log(AuditActions.POSTAL_DISPATCH_CREATED, AuditActions.POSTAL_DISPATCH, id,
                Map.of("referenceNo", referenceNo, "toTitle", toTitle)));
        return saved;
    }

    /** Edits the form fields; the reference number is never changed. @throws ApiException 404 if no such dispatch. */
    @Transactional
    public PostalDispatch update(UUID id, DispatchRequest request) {
        PostalDispatch dispatch = require(id);
        apply(dispatch, request);
        PostalDispatch saved = dispatchRepository.save(dispatch);
        auditService.log(AuditActions.POSTAL_DISPATCH_UPDATED, AuditActions.POSTAL_DISPATCH, id,
                Map.of("referenceNo", saved.getReferenceNo()));
        return saved;
    }

    /** Deletes the dispatch, its document rows, and (after commit) their files. @throws ApiException 404. */
    @Transactional
    public void delete(UUID id) {
        PostalDispatch dispatch = require(id);
        List<PostalDispatchDocument> documents = documentRepository.findByDispatchIdOrderByUploadedAt(id);
        documentRepository.deleteAll(documents);
        dispatchRepository.delete(dispatch);
        afterCommit(() -> {
            documents.forEach(d -> fileStore.delete(STORAGE_AREA, id, d.getStoredFilename()));
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.POSTAL_DISPATCH_DELETED, AuditActions.POSTAL_DISPATCH, id,
                Map.of("referenceNo", dispatch.getReferenceNo(), "documents", documents.size()));
    }

    @Transactional(readOnly = true)
    public PostalDispatch get(UUID id) {
        return require(id);
    }

    /** Search, paginated. {@code query} matches reference no, to/from title, address or note. */
    @Transactional(readOnly = true)
    public Page<PostalDispatch> search(String query, Pageable pageable) {
        Specification<PostalDispatch> spec = (root, criteriaQuery, cb) -> {
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
        return dispatchRepository.findAll(spec, pageable);
    }

    // --- Documents ------------------------------------------------------------------

    /**
     * Attaches one supporting document.
     *
     * @throws ApiException 404 if no such dispatch, 400 if the file is invalid or the dispatch already has
     *         {@value #MAX_DOCUMENTS} documents.
     */
    @Transactional
    public PostalDispatchDocument addDocument(UUID dispatchId, MultipartFile file, UUID uploadedByUserId) {
        PostalDispatch dispatch = require(dispatchId);
        if (documentRepository.countByDispatchId(dispatchId) >= MAX_DOCUMENTS) {
            throw new ApiException("A dispatch can have at most " + MAX_DOCUMENTS + " documents", HttpStatus.BAD_REQUEST);
        }
        StoredFile stored = fileStore.store(STORAGE_AREA, dispatchId, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, dispatchId, stored.storedFilename()));

        PostalDispatchDocument document = new PostalDispatchDocument();
        document.setDispatchId(dispatchId);
        document.setOriginalFilename(stored.originalFilename());
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.sizeBytes());
        document.setUploadedByUserId(uploadedByUserId);
        PostalDispatchDocument saved = documentRepository.save(document);
        auditService.log(AuditActions.POSTAL_DISPATCH_DOCUMENT_UPLOADED, AuditActions.POSTAL_DISPATCH, dispatchId,
                Map.of("referenceNo", dispatch.getReferenceNo(), "fileName", saved.getOriginalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if the document doesn't belong to this dispatch. */
    @Transactional(readOnly = true)
    public PostalDispatchDocument getDocument(UUID dispatchId, UUID documentId) {
        return documentRepository.findByIdAndDispatchId(documentId, dispatchId)
                .orElseThrow(() -> new ApiException("Document not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Resource loadDocument(PostalDispatchDocument document) {
        return fileStore.load(STORAGE_AREA, document.getDispatchId(), document.getStoredFilename());
    }

    /** @throws ApiException 404 if the document doesn't belong to this dispatch. */
    @Transactional
    public void deleteDocument(UUID dispatchId, UUID documentId) {
        PostalDispatchDocument document = getDocument(dispatchId, documentId);
        documentRepository.delete(document);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, dispatchId, document.getStoredFilename()));
        auditService.log(AuditActions.POSTAL_DISPATCH_DOCUMENT_DELETED, AuditActions.POSTAL_DISPATCH, dispatchId,
                Map.of("fileName", document.getOriginalFilename()));
    }

    // --- Response mapping -------------------------------------------------------------

    @Transactional(readOnly = true)
    public DispatchResponse toResponse(PostalDispatch dispatch) {
        return DispatchResponse.from(dispatch, documentRepository.findByDispatchIdOrderByUploadedAt(dispatch.getId()));
    }

    /** Maps a page with one query for all of its documents. */
    @Transactional(readOnly = true)
    public List<DispatchResponse> toResponses(List<PostalDispatch> dispatches) {
        if (dispatches.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PostalDispatchDocument>> byDispatch = documentRepository
                .findByDispatchIdInOrderByUploadedAt(dispatches.stream().map(PostalDispatch::getId).toList())
                .stream().collect(Collectors.groupingBy(PostalDispatchDocument::getDispatchId));
        return dispatches.stream()
                .map(d -> DispatchResponse.from(d, byDispatch.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    // --- Helpers ------------------------------------------------------------------------

    private static void apply(PostalDispatch dispatch, DispatchRequest request) {
        dispatch.setToTitle(request.toTitle().trim());
        dispatch.setFromTitle(blankToNull(request.fromTitle()));
        dispatch.setAddress(blankToNull(request.address()));
        dispatch.setNote(blankToNull(request.note()));
        dispatch.setDispatchDate(request.dispatchDate());
    }

    private PostalDispatch require(UUID id) {
        return dispatchRepository.findById(id)
                .orElseThrow(() -> new ApiException("Postal dispatch not found", HttpStatus.NOT_FOUND));
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
