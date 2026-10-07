package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Configurable enquiry references -- same rules as {@link EnquirySourceService}. */
@Service
public class EnquiryReferenceService {

    private final EnquiryReferenceRepository referenceRepository;
    private final AuditService auditService;

    public EnquiryReferenceService(EnquiryReferenceRepository referenceRepository, AuditService auditService) {
        this.referenceRepository = referenceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<EnquiryReference> listActive() {
        return referenceRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a reference with that name already exists. */
    @Transactional
    public EnquiryReference create(String name) {
        String trimmed = name.trim();
        if (referenceRepository.existsByName(trimmed)) {
            throw new ApiException("A reference named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        EnquiryReference reference = new EnquiryReference();
        reference.setName(trimmed);
        reference.setActive(true);
        EnquiryReference saved;
        try {
            saved = referenceRepository.saveAndFlush(reference);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A reference named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.ENQUIRY_REFERENCE_CREATED, AuditActions.ENQUIRY_REFERENCE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
