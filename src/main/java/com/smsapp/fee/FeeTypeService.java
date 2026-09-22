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
