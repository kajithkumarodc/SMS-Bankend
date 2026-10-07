package com.smsapp.expense;

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

/** Configurable expense heads -- same rules as {@code ComplaintTypeService}. */
@Service
public class ExpenseHeadService {

    private final ExpenseHeadRepository expenseHeadRepository;
    private final ExpenseRepository expenseRepository;
    private final AuditService auditService;

    public ExpenseHeadService(ExpenseHeadRepository expenseHeadRepository, ExpenseRepository expenseRepository,
                              AuditService auditService) {
        this.expenseHeadRepository = expenseHeadRepository;
        this.expenseRepository = expenseRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ExpenseHead> listActive() {
        return expenseHeadRepository.findByActiveTrueOrderByName();
    }

    /** @throws ApiException 409 if an expense head with that name already exists. */
    @Transactional
    public ExpenseHead create(String name, String description) {
        String trimmed = name.trim();
        if (expenseHeadRepository.existsByName(trimmed)) {
            throw new ApiException("An expense head named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        ExpenseHead head = new ExpenseHead();
        head.setName(trimmed);
        head.setDescription(description == null || description.isBlank() ? null : description.trim());
        head.setActive(true);
        ExpenseHead saved;
        try {
            saved = expenseHeadRepository.saveAndFlush(head);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("An expense head named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.EXPENSE_HEAD_CREATED, AuditActions.EXPENSE_HEAD, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such head, 409 if another head already has that name. */
    @Transactional
    public ExpenseHead update(UUID id, String name, String description) {
        ExpenseHead head = require(id);
        String trimmed = name.trim();
        if (expenseHeadRepository.existsByNameAndIdNot(trimmed, id)) {
            throw new ApiException("An expense head named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        head.setName(trimmed);
        head.setDescription(description == null || description.isBlank() ? null : description.trim());
        ExpenseHead saved;
        try {
            saved = expenseHeadRepository.saveAndFlush(head);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("An expense head named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.EXPENSE_HEAD_UPDATED, AuditActions.EXPENSE_HEAD, id, Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if no such head, 409 if expenses are filed under it. */
    @Transactional
    public void delete(UUID id) {
        ExpenseHead head = require(id);
        long inUse = expenseRepository.countByExpenseHeadId(id);
        if (inUse > 0) {
            throw new ApiException("'" + head.getName() + "' is used by " + inUse + (inUse == 1 ? " expense" : " expenses")
                    + " and can't be deleted", HttpStatus.CONFLICT);
        }
        expenseHeadRepository.delete(head);
        auditService.log(AuditActions.EXPENSE_HEAD_DELETED, AuditActions.EXPENSE_HEAD, id, Map.of("name", head.getName()));
    }

    private ExpenseHead require(UUID id) {
        return expenseHeadRepository.findById(id)
                .orElseThrow(() -> new ApiException("Expense head not found", HttpStatus.NOT_FOUND));
    }
}
