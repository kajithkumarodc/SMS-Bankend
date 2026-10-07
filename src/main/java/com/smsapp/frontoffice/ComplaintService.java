package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.ComplaintDtos.ComplaintRequest;
import com.smsapp.frontoffice.ComplaintDtos.ComplaintResponse;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Front Office / Complaints. The "Complain #" comes from a database identity column. The optional document is
 * stored with {@link FrontOfficeFileStore}; replaced or deleted files are removed only after the database commit,
 * and a file written for a save that rolls back is removed, so disk and database never disagree.
 */
@Service
public class ComplaintService {

    static final String STORAGE_AREA = "complaints";

    private final ComplaintRepository complaintRepository;
    private final ComplaintTypeRepository complaintTypeRepository;
    private final EnquirySourceRepository sourceRepository;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public ComplaintService(ComplaintRepository complaintRepository, ComplaintTypeRepository complaintTypeRepository,
                            EnquirySourceRepository sourceRepository, FrontOfficeFileStore fileStore,
                            AuditService auditService) {
        this.complaintRepository = complaintRepository;
        this.complaintTypeRepository = complaintTypeRepository;
        this.sourceRepository = sourceRepository;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** @throws ApiException 404 if the complaint type or source doesn't exist. */
    @Transactional
    public Complaint create(ComplaintRequest request, UUID createdByUserId) {
        Complaint complaint = new Complaint();
        apply(complaint, request);
        complaint.setCreatedByUserId(createdByUserId);
        // saveAndFlush so the database-assigned complaint number is read back now.
        Complaint saved = complaintRepository.saveAndFlush(complaint);
        auditService.log(AuditActions.COMPLAINT_CREATED, AuditActions.COMPLAINT, saved.getId(),
                Map.of("complaintNo", saved.getComplaintNo(), "complainBy", saved.getComplainBy()));
        return saved;
    }

    /** Same rules as {@link #create}; the attachment is left as it is. @throws ApiException 404 if no such complaint. */
    @Transactional
    public Complaint update(UUID id, ComplaintRequest request) {
        Complaint complaint = require(id);
        apply(complaint, request);
        Complaint saved = complaintRepository.save(complaint);
        auditService.log(AuditActions.COMPLAINT_UPDATED, AuditActions.COMPLAINT, id,
                Map.of("complaintNo", saved.getComplaintNo()));
        return saved;
    }

    /** Deletes the complaint and (after commit) its document. @throws ApiException 404 if no such complaint. */
    @Transactional
    public void delete(UUID id) {
        Complaint complaint = require(id);
        String storedFile = complaint.getAttachmentStoredFilename();
        complaintRepository.delete(complaint);
        afterCommit(() -> {
            if (storedFile != null) {
                fileStore.delete(STORAGE_AREA, id, storedFile);
            }
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.COMPLAINT_DELETED, AuditActions.COMPLAINT, id,
                Map.of("complaintNo", complaint.getComplaintNo(), "complainBy", complaint.getComplainBy()));
    }

    @Transactional(readOnly = true)
    public Complaint get(UUID id) {
        return require(id);
    }

    /** Search, paginated. {@code query} matches complain by, phone, description, action taken, assigned or note. */
    @Transactional(readOnly = true)
    public Page<Complaint> search(String query, Pageable pageable) {
        Specification<Complaint> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String trimmed = query.trim();
                String like = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
                List<Predicate> matches = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("complainBy")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("actionTaken")), like),
                        cb.like(cb.lower(root.get("assigned")), like),
                        cb.like(cb.lower(root.get("note")), like)));
                // "417" or "#417" also finds complaint number 417.
                String digits = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
                if (!digits.isEmpty() && digits.length() <= 18 && digits.chars().allMatch(Character::isDigit)) {
                    matches.add(cb.equal(root.get("complaintNo"), Long.parseLong(digits)));
                }
                predicates.add(cb.or(matches.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return complaintRepository.findAll(spec, pageable);
    }

    // --- Attachment ---------------------------------------------------------------------

    /**
     * Attaches (or replaces) the complaint's document.
     *
     * @throws ApiException 404 if no such complaint, 400 if the file is empty, over 10 MB or not an allowed type.
     */
    @Transactional
    public Complaint attach(UUID id, MultipartFile file) {
        Complaint complaint = require(id);
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        String previous = complaint.getAttachmentStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        complaint.setAttachmentOriginalFilename(stored.originalFilename());
        complaint.setAttachmentStoredFilename(stored.storedFilename());
        complaint.setAttachmentContentType(stored.contentType());
        complaint.setAttachmentSizeBytes(stored.sizeBytes());
        Complaint saved = complaintRepository.save(complaint);
        auditService.log(AuditActions.COMPLAINT_ATTACHMENT_UPLOADED, AuditActions.COMPLAINT, id,
                Map.of("complaintNo", saved.getComplaintNo(), "fileName", stored.originalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if no such complaint or it has no document. */
    @Transactional
    public Complaint removeAttachment(UUID id) {
        Complaint complaint = require(id);
        String storedFile = complaint.getAttachmentStoredFilename();
        if (storedFile == null) {
            throw new ApiException("This complaint has no attached document", HttpStatus.NOT_FOUND);
        }
        complaint.setAttachmentOriginalFilename(null);
        complaint.setAttachmentStoredFilename(null);
        complaint.setAttachmentContentType(null);
        complaint.setAttachmentSizeBytes(null);
        Complaint saved = complaintRepository.save(complaint);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        auditService.log(AuditActions.COMPLAINT_ATTACHMENT_REMOVED, AuditActions.COMPLAINT, id,
                Map.of("complaintNo", saved.getComplaintNo()));
        return saved;
    }

    /** @throws ApiException 404 if there's no document or the file is missing. */
    @Transactional(readOnly = true)
    public Resource loadAttachment(Complaint complaint) {
        if (!complaint.hasAttachment()) {
            throw new ApiException("This complaint has no attached document", HttpStatus.NOT_FOUND);
        }
        return fileStore.load(STORAGE_AREA, complaint.getId(), complaint.getAttachmentStoredFilename());
    }

    // --- Response mapping -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public ComplaintResponse toResponse(Complaint complaint) {
        return toResponses(List.of(complaint)).get(0);
    }

    /** Maps a page with one lookup for complaint types and one for sources. */
    @Transactional(readOnly = true)
    public List<ComplaintResponse> toResponses(List<Complaint> complaints) {
        Set<UUID> typeIds = complaints.stream().map(Complaint::getComplaintTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> sourceIds = complaints.stream().map(Complaint::getSourceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> types = typeIds.isEmpty() ? Map.of() : complaintTypeRepository.findAllById(typeIds).stream()
                .collect(Collectors.toMap(ComplaintType::getId, ComplaintType::getName));
        Map<UUID, String> sources = sourceIds.isEmpty() ? Map.of() : sourceRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(EnquirySource::getId, EnquirySource::getName));
        return complaints.stream()
                .map(c -> ComplaintResponse.from(c,
                        c.getComplaintTypeId() == null ? null : types.get(c.getComplaintTypeId()),
                        c.getSourceId() == null ? null : sources.get(c.getSourceId())))
                .toList();
    }

    // --- Helpers ----------------------------------------------------------------------------

    private void apply(Complaint complaint, ComplaintRequest request) {
        if (request.complaintTypeId() != null && !complaintTypeRepository.existsById(request.complaintTypeId())) {
            throw new ApiException("Complaint type not found", HttpStatus.NOT_FOUND);
        }
        if (request.sourceId() != null && !sourceRepository.existsById(request.sourceId())) {
            throw new ApiException("Source not found", HttpStatus.NOT_FOUND);
        }
        complaint.setComplaintTypeId(request.complaintTypeId());
        complaint.setSourceId(request.sourceId());
        complaint.setComplainBy(request.complainBy().trim());
        complaint.setPhone(blankToNull(request.phone()));
        complaint.setComplaintDate(request.complaintDate());
        complaint.setDescription(blankToNull(request.description()));
        complaint.setActionTaken(blankToNull(request.actionTaken()));
        complaint.setAssigned(blankToNull(request.assigned()));
        complaint.setNote(blankToNull(request.note()));
    }

    private Complaint require(UUID id) {
        return complaintRepository.findById(id)
                .orElseThrow(() -> new ApiException("Complaint not found", HttpStatus.NOT_FOUND));
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
