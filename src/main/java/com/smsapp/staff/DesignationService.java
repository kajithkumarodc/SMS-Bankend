package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human Resource > Designation: the list of designations staff are assigned to. Names are unique, ignoring case.
 * A designation that staff belong to cannot be deleted; renaming one renames it on their profiles too.
 */
@Service
public class DesignationService {

    private final DesignationRepository designationRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final AuditService auditService;

    public DesignationService(DesignationRepository designationRepository, StaffProfileRepository staffProfileRepository,
                             AuditService auditService) {
        this.designationRepository = designationRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.auditService = auditService;
    }

    /** The active designations, ordered by name. */
    @Transactional(readOnly = true)
    public List<Designation> list() {
        return designationRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a designation with that name already exists. */
    @Transactional
    public Designation create(String name) {
        String trimmed = name.trim();
        if (designationRepository.existsByNameIgnoreCase(trimmed)) {
            throw nameTaken(trimmed);
        }
        Designation designation = new Designation();
        designation.setName(trimmed);
        designation.setActive(true);
        Designation saved;
        try {
            saved = designationRepository.saveAndFlush(designation);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        auditService.log(AuditActions.DESIGNATION_CREATED, AuditActions.DESIGNATION, saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such designation, 409 if another one already has that name. */
    @Transactional
    public Designation rename(UUID id, String name) {
        Designation designation = require(id);
        String trimmed = name.trim();
        if (designationRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw nameTaken(trimmed);
        }
        designation.setName(trimmed);
        Designation saved;
        try {
            saved = designationRepository.saveAndFlush(designation);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        staffProfileRepository.renameDesignation(id, trimmed);
        auditService.log(AuditActions.DESIGNATION_UPDATED, AuditActions.DESIGNATION, id, Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such designation, 409 if staff belong to it. */
    @Transactional
    public void delete(UUID id) {
        Designation designation = require(id);
        long inUse = staffProfileRepository.countByDesignationId(id);
        if (inUse > 0) {
            throw new ApiException("'" + designation.getName() + "' is used by " + inUse + (inUse == 1 ? " staff member" : " staff members")
                    + " and can't be deleted", HttpStatus.CONFLICT);
        }
        designationRepository.delete(designation);
        auditService.log(AuditActions.DESIGNATION_DELETED, AuditActions.DESIGNATION, id, Map.of("name", designation.getName()));
    }

    private Designation require(UUID id) {
        return designationRepository.findById(id).orElseThrow(() -> new ApiException("Designation not found", HttpStatus.NOT_FOUND));
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A designation named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
