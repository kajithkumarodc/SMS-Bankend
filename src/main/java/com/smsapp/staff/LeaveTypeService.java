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
 * Human Resource > Leave Type: the list of leave types a request can be filed under. Names are unique, ignoring
 * case. A type that requests use cannot be deleted; renaming one renames it on its requests too.
 */
@Service
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AuditService auditService;

    public LeaveTypeService(LeaveTypeRepository leaveTypeRepository, LeaveRequestRepository leaveRequestRepository,
                            AuditService auditService) {
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.auditService = auditService;
    }

    /** The active leave types, ordered by name. */
    @Transactional(readOnly = true)
    public List<LeaveType> list() {
        return leaveTypeRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a leave type with that name already exists. */
    @Transactional
    public LeaveType create(String name) {
        String trimmed = name.trim();
        if (leaveTypeRepository.existsByNameIgnoreCase(trimmed)) {
            throw nameTaken(trimmed);
        }
        LeaveType type = new LeaveType();
        type.setName(trimmed);
        type.setActive(true);
        LeaveType saved;
        try {
            saved = leaveTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        auditService.log(AuditActions.LEAVE_TYPE_CREATED, AuditActions.LEAVE_TYPE, saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such leave type, 409 if another one already has that name. */
    @Transactional
    public LeaveType rename(UUID id, String name) {
        LeaveType type = require(id);
        String trimmed = name.trim();
        if (leaveTypeRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw nameTaken(trimmed);
        }
        type.setName(trimmed);
        LeaveType saved;
        try {
            saved = leaveTypeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        leaveRequestRepository.renameLeaveType(id, trimmed);
        auditService.log(AuditActions.LEAVE_TYPE_UPDATED, AuditActions.LEAVE_TYPE, id, Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such leave type, 409 if leave requests use it. */
    @Transactional
    public void delete(UUID id) {
        LeaveType type = require(id);
        long inUse = leaveRequestRepository.countByLeaveTypeId(id);
        if (inUse > 0) {
            throw new ApiException("'" + type.getName() + "' is used by " + inUse + (inUse == 1 ? " leave request" : " leave requests")
                    + " and can't be deleted", HttpStatus.CONFLICT);
        }
        leaveTypeRepository.delete(type);
        auditService.log(AuditActions.LEAVE_TYPE_DELETED, AuditActions.LEAVE_TYPE, id, Map.of("name", type.getName()));
    }

    private LeaveType require(UUID id) {
        return leaveTypeRepository.findById(id).orElseThrow(() -> new ApiException("Leave type not found", HttpStatus.NOT_FOUND));
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A leave type named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
