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
 * Human Resource > Department: the list of departments staff are assigned to. Names are unique, ignoring case.
 * A department that staff belong to cannot be deleted; renaming one renames it on their profiles too.
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final AuditService auditService;

    public DepartmentService(DepartmentRepository departmentRepository, StaffProfileRepository staffProfileRepository,
                             AuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.auditService = auditService;
    }

    /** The active departments, ordered by name. */
    @Transactional(readOnly = true)
    public List<Department> list() {
        return departmentRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if a department with that name already exists. */
    @Transactional
    public Department create(String name) {
        String trimmed = name.trim();
        if (departmentRepository.existsByNameIgnoreCase(trimmed)) {
            throw nameTaken(trimmed);
        }
        Department department = new Department();
        department.setName(trimmed);
        department.setActive(true);
        Department saved;
        try {
            saved = departmentRepository.saveAndFlush(department);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        auditService.log(AuditActions.DEPARTMENT_CREATED, AuditActions.DEPARTMENT, saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such department, 409 if another one already has that name. */
    @Transactional
    public Department rename(UUID id, String name) {
        Department department = require(id);
        String trimmed = name.trim();
        if (departmentRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw nameTaken(trimmed);
        }
        department.setName(trimmed);
        Department saved;
        try {
            saved = departmentRepository.saveAndFlush(department);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(trimmed);
        }
        staffProfileRepository.renameDepartment(id, trimmed);
        auditService.log(AuditActions.DEPARTMENT_UPDATED, AuditActions.DEPARTMENT, id, Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such department, 409 if staff belong to it. */
    @Transactional
    public void delete(UUID id) {
        Department department = require(id);
        long inUse = staffProfileRepository.countByDepartmentId(id);
        if (inUse > 0) {
            throw new ApiException("'" + department.getName() + "' is used by " + inUse + (inUse == 1 ? " staff member" : " staff members")
                    + " and can't be deleted", HttpStatus.CONFLICT);
        }
        departmentRepository.delete(department);
        auditService.log(AuditActions.DEPARTMENT_DELETED, AuditActions.DEPARTMENT, id, Map.of("name", department.getName()));
    }

    private Department require(UUID id) {
        return departmentRepository.findById(id).orElseThrow(() -> new ApiException("Department not found", HttpStatus.NOT_FOUND));
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A department named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
