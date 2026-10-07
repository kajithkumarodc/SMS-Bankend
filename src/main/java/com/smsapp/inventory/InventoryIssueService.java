package com.smsapp.inventory;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.inventory.InventoryDtos.IssueRequest;
import com.smsapp.inventory.InventoryDtos.IssueResponse;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inventory > Issue Item: giving units of an item to a staff member and getting them back. Issuing takes the units
 * from the item's stock, returning (or deleting an issue that is still out) gives them back.
 */
@Service
public class InventoryIssueService {

    private static final String ACTIVE = "ACTIVE";

    private final InventoryIssueRepository issueRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryCategoryRepository categoryRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public InventoryIssueService(InventoryIssueRepository issueRepository, InventoryItemRepository itemRepository,
                                 InventoryCategoryRepository categoryRepository, StaffProfileRepository staffRepository,
                                 UserRepository userRepository, AuditService auditService) {
        this.issueRepository = issueRepository;
        this.itemRepository = itemRepository;
        this.categoryRepository = categoryRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** Every issue, latest issue date first. */
    @Transactional(readOnly = true)
    public List<IssueResponse> list() {
        return toResponses(issueRepository.findAllByOrderByIssueDateDescCreatedAtDesc());
    }

    /**
     * @throws ApiException 404 if the item or a staff member doesn't exist, 400 if a staff member is disabled or the
     *         return date is before the issue date, 409 if the item has fewer units in stock than asked for.
     */
    @Transactional
    public IssueResponse issue(IssueRequest request) {
        if (request.returnDate() != null && request.returnDate().isBefore(request.issueDate())) {
            throw new ApiException("The return date can't be before the issue date", HttpStatus.BAD_REQUEST);
        }
        requireActiveStaff(request.issueToStaffId(), "Issue To");
        requireActiveStaff(request.issuedByStaffId(), "Issue By");
        InventoryItem item = itemRepository.findForUpdate(request.itemId())
                .orElseThrow(() -> new ApiException("Item not found", HttpStatus.NOT_FOUND));
        if (item.getStock() < request.quantity()) {
            throw new ApiException("Only " + item.getStock() + " of '" + item.getName() + "' "
                    + (item.getStock() == 1 ? "is" : "are") + " in stock", HttpStatus.CONFLICT);
        }
        item.setStock(item.getStock() - request.quantity());
        itemRepository.save(item);

        InventoryIssue issue = new InventoryIssue();
        issue.setItemId(item.getId());
        issue.setQuantity(request.quantity());
        issue.setIssueToStaffId(request.issueToStaffId());
        issue.setIssuedByStaffId(request.issuedByStaffId());
        issue.setIssueDate(request.issueDate());
        issue.setReturnDate(request.returnDate());
        String note = request.note() == null ? null : request.note().trim();
        issue.setNote(note == null || note.isEmpty() ? null : note);
        InventoryIssue saved = issueRepository.save(issue);
        auditService.log(AuditActions.INVENTORY_ITEM_ISSUED, AuditActions.INVENTORY_ISSUE, saved.getId(),
                Map.of("item", item.getName(), "quantity", saved.getQuantity()));
        return toResponses(List.of(saved)).get(0);
    }

    /** Marks an issue as returned and puts its units back in stock. @throws ApiException 404 if none, 409 if already returned. */
    @Transactional
    public IssueResponse returnIssue(UUID id) {
        InventoryIssue issue = require(id);
        if (InventoryIssue.RETURNED.equals(issue.getStatus())) {
            throw new ApiException("This item has already been returned", HttpStatus.CONFLICT);
        }
        InventoryItem item = itemRepository.findForUpdate(issue.getItemId())
                .orElseThrow(() -> new ApiException("Item not found", HttpStatus.NOT_FOUND));
        item.setStock(item.getStock() + issue.getQuantity());
        itemRepository.save(item);
        issue.setStatus(InventoryIssue.RETURNED);
        issue.setReturnedAt(OffsetDateTime.now());
        InventoryIssue saved = issueRepository.save(issue);
        auditService.log(AuditActions.INVENTORY_ITEM_RETURNED, AuditActions.INVENTORY_ISSUE, id,
                Map.of("item", item.getName(), "quantity", saved.getQuantity()));
        return toResponses(List.of(saved)).get(0);
    }

    /** Deletes an issue; if its units are still out they go back in stock. @throws ApiException 404 if none. */
    @Transactional
    public void delete(UUID id) {
        InventoryIssue issue = require(id);
        if (InventoryIssue.ISSUED.equals(issue.getStatus())) {
            itemRepository.findForUpdate(issue.getItemId()).ifPresent(item -> {
                item.setStock(item.getStock() + issue.getQuantity());
                itemRepository.save(item);
            });
        }
        issueRepository.delete(issue);
        auditService.log(AuditActions.INVENTORY_ISSUE_DELETED, AuditActions.INVENTORY_ISSUE, id,
                Map.of("quantity", issue.getQuantity(), "status", issue.getStatus()));
    }

    private InventoryIssue require(UUID id) {
        return issueRepository.findById(id).orElseThrow(() -> new ApiException("Issue not found", HttpStatus.NOT_FOUND));
    }

    private void requireActiveStaff(UUID staffProfileId, String field) {
        StaffProfile profile = staffRepository.findById(staffProfileId)
                .orElseThrow(() -> new ApiException(field + ": staff member not found", HttpStatus.NOT_FOUND));
        if (!ACTIVE.equals(profile.getStatus())) {
            throw new ApiException(field + ": this staff member is disabled", HttpStatus.BAD_REQUEST);
        }
    }

    private List<IssueResponse> toResponses(List<InventoryIssue> issues) {
        Map<UUID, InventoryItem> items = itemRepository.findAll().stream().collect(Collectors.toMap(InventoryItem::getId, Function.identity()));
        Map<UUID, String> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(InventoryCategory::getId, InventoryCategory::getName));
        Map<UUID, StaffProfile> staff = new HashMap<>();
        for (StaffProfile p : staffRepository.findAll()) {
            staff.put(p.getId(), p);
        }
        Map<UUID, String> names = userRepository.findAllById(staff.values().stream().map(StaffProfile::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        return issues.stream().map(i -> {
            InventoryItem item = items.get(i.getItemId());
            StaffProfile to = staff.get(i.getIssueToStaffId());
            StaffProfile by = staff.get(i.getIssuedByStaffId());
            return new IssueResponse(i.getId(), i.getItemId(), item == null ? null : item.getName(),
                    item == null ? null : categories.get(item.getCategoryId()), i.getNote(), i.getIssueDate(), i.getReturnDate(),
                    i.getIssueToStaffId(), to == null ? null : names.get(to.getUserId()), to == null ? null : to.getEmployeeCode(),
                    i.getIssuedByStaffId(), by == null ? null : names.get(by.getUserId()), by == null ? null : by.getEmployeeCode(),
                    i.getQuantity(), i.getStatus(), i.getReturnedAt());
        }).toList();
    }
}
