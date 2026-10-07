package com.smsapp.fee;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Configurable fee types (plan Phase 5 part B: "Administrators should be able to configure fee types"). */
@Service
public class FeeTypeService {

    private final FeeTypeRepository feeTypeRepository;
    private final AuditService auditService;

    public FeeTypeService(FeeTypeRepository feeTypeRepository, AuditService auditService) {
        this.feeTypeRepository = feeTypeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<FeeType> listActive() {
        return feeTypeRepository.findByActiveTrueOrderByName();
    }

    @Transactional(readOnly = true)
    public List<FeeType> listAll() {
        return feeTypeRepository.findAllByOrderByName();
    }

    /**
     * Rename and/or (de)activate a fee type. Types are never deleted -- fee lines keep pointing at them -- so
     * "remove" is deactivate: it disappears from pickers but old fee structures still show its name.
     *
     * @throws ApiException 404 if missing, 409 if another type already has the name.
     */
    @Transactional
    public FeeType update(java.util.UUID id, String name, Boolean active) {
        FeeType type = feeTypeRepository.findById(id)
                .orElseThrow(() -> new ApiException("Fee type not found", HttpStatus.NOT_FOUND));
        String trimmed = name.trim();
        if (!type.getName().equals(trimmed) && feeTypeRepository.existsByName(trimmed)) {
            throw new ApiException("A fee type named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        String before = type.getName();
        type.setName(trimmed);
        if (active != null) {
            type.setActive(active);
        }
        FeeType saved = feeTypeRepository.save(type);
        auditService.log(AuditActions.FEE_TYPE_UPDATED, AuditActions.FEE_TYPE, id,
                Map.of("from", before, "to", saved.getName(), "active", saved.isActive()));
        return saved;
    }

    /** @throws ApiException 409 if a type with that name already exists. */
    @Transactional
    public FeeType create(String name) {
        String trimmed = name.trim();
        if (feeTypeRepository.existsByName(trimmed)) {
            throw new ApiException("A fee type named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        FeeType type = new FeeType();
        type.setName(trimmed);
        type.setActive(true);
        FeeType saved;
        try {
            saved = feeTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A fee type named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.FEE_TYPE_CREATED, AuditActions.FEE_TYPE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
