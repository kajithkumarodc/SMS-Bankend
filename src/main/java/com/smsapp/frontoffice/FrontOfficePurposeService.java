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

/** Configurable visit purposes for the Visitor Book -- same rules as {@link EnquirySourceService}. */
@Service
public class FrontOfficePurposeService {

    private final FrontOfficePurposeRepository purposeRepository;
    private final AuditService auditService;

    public FrontOfficePurposeService(FrontOfficePurposeRepository purposeRepository, AuditService auditService) {
        this.purposeRepository = purposeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<FrontOfficePurpose> listActive() {
        return purposeRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a purpose with that name already exists. */
    @Transactional
    public FrontOfficePurpose create(String name) {
        String trimmed = name.trim();
        if (purposeRepository.existsByName(trimmed)) {
            throw new ApiException("A purpose named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        FrontOfficePurpose purpose = new FrontOfficePurpose();
        purpose.setName(trimmed);
        purpose.setActive(true);
        FrontOfficePurpose saved;
        try {
            saved = purposeRepository.saveAndFlush(purpose);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A purpose named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.FRONT_OFFICE_PURPOSE_CREATED, AuditActions.FRONT_OFFICE_PURPOSE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
