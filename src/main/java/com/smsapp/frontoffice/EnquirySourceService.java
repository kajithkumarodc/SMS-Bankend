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

/** Configurable enquiry sources (plan: "Make sources configurable rather than hardcoded"). */
@Service
public class EnquirySourceService {

    private final EnquirySourceRepository enquirySourceRepository;
    private final AuditService auditService;

    public EnquirySourceService(EnquirySourceRepository enquirySourceRepository, AuditService auditService) {
        this.enquirySourceRepository = enquirySourceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<EnquirySource> listActive() {
        return enquirySourceRepository.findByActiveTrueOrderByName();
    }

    @Transactional(readOnly = true)
    public List<EnquirySource> listAll() {
        return enquirySourceRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a source with that name already exists. */
    @Transactional
    public EnquirySource create(String name) {
        String trimmed = name.trim();
        if (enquirySourceRepository.existsByName(trimmed)) {
            throw new ApiException("A source named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        EnquirySource source = new EnquirySource();
        source.setName(trimmed);
        source.setActive(true);
        EnquirySource saved;
        try {
            saved = enquirySourceRepository.saveAndFlush(source);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A source named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.ENQUIRY_SOURCE_CREATED, AuditActions.ENQUIRY_SOURCE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
