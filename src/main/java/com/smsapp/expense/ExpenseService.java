package com.smsapp.expense;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.expense.ExpenseDtos.ExpenseRequest;
import com.smsapp.expense.ExpenseDtos.ExpenseResponse;
import com.smsapp.frontoffice.FrontOfficeFileStore;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Expenses. The optional document is stored with {@link FrontOfficeFileStore} (same rules as the other attached
 * documents); replaced or deleted files are removed only after the database commit, and a file written for a save
 * that rolls back is removed, so disk and database never disagree.
 */
@Service
public class ExpenseService {

    static final String STORAGE_AREA = "expenses";

    @PersistenceContext
    private EntityManager entityManager;

    private final ExpenseRepository expenseRepository;
    private final ExpenseHeadRepository expenseHeadRepository;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public ExpenseService(ExpenseRepository expenseRepository, ExpenseHeadRepository expenseHeadRepository,
                          FrontOfficeFileStore fileStore, AuditService auditService) {
        this.expenseRepository = expenseRepository;
        this.expenseHeadRepository = expenseHeadRepository;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** @throws ApiException 404 if the expense head doesn't exist, 400 if it is inactive. */
    @Transactional
    public Expense create(ExpenseRequest request, UUID createdByUserId) {
        Expense expense = new Expense();
        apply(expense, request);
        expense.setCreatedByUserId(createdByUserId);
        Expense saved = expenseRepository.saveAndFlush(expense);
        auditService.log(AuditActions.EXPENSE_CREATED, AuditActions.EXPENSE, saved.getId(),
                Map.of("name", saved.getName(), "amount", saved.getAmount().toPlainString()));
        return saved;
    }

    /** Same rules as {@link #create}; the attachment is left as it is. @throws ApiException 404 if no such expense. */
    @Transactional
    public Expense update(UUID id, ExpenseRequest request) {
        Expense expense = require(id);
        apply(expense, request);
        Expense saved = expenseRepository.save(expense);
        auditService.log(AuditActions.EXPENSE_UPDATED, AuditActions.EXPENSE, id,
                Map.of("name", saved.getName(), "amount", saved.getAmount().toPlainString()));
        return saved;
    }

    /** Deletes the expense and (after commit) its document. @throws ApiException 404 if no such expense. */
    @Transactional
    public void delete(UUID id) {
        Expense expense = require(id);
        String storedFile = expense.getAttachmentStoredFilename();
        expenseRepository.delete(expense);
        afterCommit(() -> {
            if (storedFile != null) {
                fileStore.delete(STORAGE_AREA, id, storedFile);
            }
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.EXPENSE_DELETED, AuditActions.EXPENSE, id,
                Map.of("name", expense.getName(), "amount", expense.getAmount().toPlainString()));
    }

    @Transactional(readOnly = true)
    public Expense get(UUID id) {
        return require(id);
    }

    /**
     * Search, paginated. Each of {@code query} and {@code filter} (both optional, both must match) matches name,
     * description, invoice number or expense head name; {@code from}/{@code to} bound the expense date, inclusive.
     */
    @Transactional(readOnly = true)
    public Page<Expense> search(String query, String filter, LocalDate from, LocalDate to, Pageable pageable) {
        return expenseRepository.findAll(matching(query, filter, from, to), pageable);
    }

    /** Sum of the amounts {@link #search} would return across all pages; zero when nothing matches. */
    @Transactional(readOnly = true)
    public BigDecimal total(String query, String filter, LocalDate from, LocalDate to) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> cq = cb.createQuery(BigDecimal.class);
        Root<Expense> root = cq.from(Expense.class);
        cq.select(cb.sum(root.<BigDecimal>get("amount")));
        cq.where(matching(query, filter, from, to).toPredicate(root, cq, cb));
        BigDecimal sum = entityManager.createQuery(cq).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private static Specification<Expense> matching(String query, String filter, LocalDate from, LocalDate to) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String term : new String[] {query, filter}) {
                if (term != null && !term.isBlank()) {
                    String like = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("name")), like),
                            cb.like(cb.lower(root.get("description")), like),
                            cb.like(cb.lower(root.get("invoiceNumber")), like),
                            cb.like(cb.lower(root.get("expenseHead").get("name")), like)));
                }
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("expenseDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("expenseDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // --- Attachment ---------------------------------------------------------------------

    /**
     * Attaches (or replaces) the expense's document.
     *
     * @throws ApiException 404 if no such expense, 400 if the file is empty, over 10 MB or not an allowed type.
     */
    @Transactional
    public Expense attach(UUID id, MultipartFile file) {
        Expense expense = require(id);
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        String previous = expense.getAttachmentStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        expense.setAttachmentOriginalFilename(stored.originalFilename());
        expense.setAttachmentStoredFilename(stored.storedFilename());
        expense.setAttachmentContentType(stored.contentType());
        expense.setAttachmentSizeBytes(stored.sizeBytes());
        Expense saved = expenseRepository.save(expense);
        auditService.log(AuditActions.EXPENSE_ATTACHMENT_UPLOADED, AuditActions.EXPENSE, id,
                Map.of("fileName", stored.originalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if no such expense or it has no document. */
    @Transactional
    public Expense removeAttachment(UUID id) {
        Expense expense = require(id);
        String storedFile = expense.getAttachmentStoredFilename();
        if (storedFile == null) {
            throw new ApiException("This expense has no attached document", HttpStatus.NOT_FOUND);
        }
        expense.setAttachmentOriginalFilename(null);
        expense.setAttachmentStoredFilename(null);
        expense.setAttachmentContentType(null);
        expense.setAttachmentSizeBytes(null);
        Expense saved = expenseRepository.save(expense);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        auditService.log(AuditActions.EXPENSE_ATTACHMENT_REMOVED, AuditActions.EXPENSE, id, Map.of("name", saved.getName()));
        return saved;
    }

    /** @throws ApiException 404 if there's no document or the file is missing. */
    @Transactional(readOnly = true)
    public Resource loadAttachment(Expense expense) {
        if (!expense.hasAttachment()) {
            throw new ApiException("This expense has no attached document", HttpStatus.NOT_FOUND);
        }
        return fileStore.load(STORAGE_AREA, expense.getId(), expense.getAttachmentStoredFilename());
    }

    // --- Response mapping -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public ExpenseResponse toResponse(Expense expense) {
        return toResponses(List.of(expense)).get(0);
    }

    /** Maps a page with one lookup for expense heads. */
    @Transactional(readOnly = true)
    public List<ExpenseResponse> toResponses(List<Expense> expenses) {
        Set<UUID> headIds = expenses.stream().map(Expense::getExpenseHeadId).collect(Collectors.toSet());
        Map<UUID, String> heads = headIds.isEmpty() ? Map.of() : expenseHeadRepository.findAllById(headIds).stream()
                .collect(Collectors.toMap(ExpenseHead::getId, ExpenseHead::getName));
        return expenses.stream().map(e -> ExpenseResponse.from(e, heads.get(e.getExpenseHeadId()))).toList();
    }

    // --- Helpers ----------------------------------------------------------------------------

    private void apply(Expense expense, ExpenseRequest request) {
        ExpenseHead head = expenseHeadRepository.findById(request.expenseHeadId())
                .orElseThrow(() -> new ApiException("Expense head not found", HttpStatus.NOT_FOUND));
        // An inactive head can stay on an expense that already has it, but can't be newly picked.
        if (!head.isActive() && !head.getId().equals(expense.getExpenseHeadId())) {
            throw new ApiException("That expense head is no longer active", HttpStatus.BAD_REQUEST);
        }
        expense.setExpenseHeadId(head.getId());
        expense.setName(request.name().trim());
        expense.setInvoiceNumber(blankToNull(request.invoiceNumber()));
        expense.setExpenseDate(request.expenseDate());
        expense.setAmount(request.amount());
        expense.setDescription(blankToNull(request.description()));
    }

    private Expense require(UUID id) {
        return expenseRepository.findById(id)
                .orElseThrow(() -> new ApiException("Expense not found", HttpStatus.NOT_FOUND));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void onRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
