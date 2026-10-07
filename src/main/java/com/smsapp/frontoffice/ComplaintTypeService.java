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

/** Configurable complaint types -- same rules as {@link EnquirySourceService}. */
@Service
public class ComplaintTypeService {

    private final ComplaintTypeRepository complaintTypeRepository;
    private final AuditService auditService;

    public ComplaintTypeService(ComplaintTypeRepository complaintTypeRepository, AuditService auditService) {
        this.complaintTypeRepository = complaintTypeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ComplaintType> listActive() {
        return complaintTypeRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a complaint type with that name already exists. */
    @Transactional
    public ComplaintType create(String name) {
        String trimmed = name.trim();
        if (complaintTypeRepository.existsByName(trimmed)) {
            throw new ApiException("A complaint type named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        ComplaintType type = new ComplaintType();
        type.setName(trimmed);
        type.setActive(true);
        ComplaintType saved;
        try {
            saved = complaintTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A complaint type named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.COMPLAINT_TYPE_CREATED, AuditActions.COMPLAINT_TYPE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
