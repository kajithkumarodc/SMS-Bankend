package com.smsapp.inventory;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.inventory.InventoryDtos.ItemRequest;
import com.smsapp.inventory.InventoryDtos.ItemResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inventory > Item Category and Add Item: the categories and the items in them. Names are unique ignoring case
 * (an item name per category). A category that has items, and an item that has been issued, cannot be deleted.
 */
@Service
public class InventoryCatalogService {

    private final InventoryCategoryRepository categoryRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryIssueRepository issueRepository;
    private final InventoryStockEntryRepository stockRepository;
    private final AuditService auditService;

    public InventoryCatalogService(InventoryCategoryRepository categoryRepository, InventoryItemRepository itemRepository,
                                   InventoryIssueRepository issueRepository, InventoryStockEntryRepository stockRepository,
                                   AuditService auditService) {
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.issueRepository = issueRepository;
        this.stockRepository = stockRepository;
        this.auditService = auditService;
    }

    // --- Categories -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryCategory> categories() {
        return categoryRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a category with that name already exists. */
    @Transactional
    public InventoryCategory createCategory(String name, String description) {
        String trimmed = name.trim();
        if (categoryRepository.existsByNameIgnoreCase(trimmed)) {
            throw categoryNameTaken(trimmed);
        }
        InventoryCategory category = new InventoryCategory();
        category.setName(trimmed);
        category.setDescription(blankToNull(description));
        InventoryCategory saved = saveCategory(category, trimmed);
        auditService.log(AuditActions.INVENTORY_CATEGORY_CREATED, AuditActions.INVENTORY_CATEGORY, saved.getId(), Map.of("name", trimmed));
        return saved;
    }

    /** @throws ApiException 404 if no such category, 409 if another one already has that name. */
    @Transactional
    public InventoryCategory updateCategory(UUID id, String name, String description) {
        InventoryCategory category = requireCategory(id);
        String trimmed = name.trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw categoryNameTaken(trimmed);
        }
        category.setName(trimmed);
        category.setDescription(blankToNull(description));
        InventoryCategory saved = saveCategory(category, trimmed);
        auditService.log(AuditActions.INVENTORY_CATEGORY_UPDATED, AuditActions.INVENTORY_CATEGORY, id, Map.of("name", trimmed));
        return saved;
    }

    /** @throws ApiException 404 if no such category, 409 if it has items. */
    @Transactional
    public void deleteCategory(UUID id) {
        InventoryCategory category = requireCategory(id);
        if (itemRepository.existsByCategoryId(id)) {
            throw new ApiException("'" + category.getName() + "' has items and can't be deleted", HttpStatus.CONFLICT);
        }
        categoryRepository.delete(category);
        auditService.log(AuditActions.INVENTORY_CATEGORY_DELETED, AuditActions.INVENTORY_CATEGORY, id, Map.of("name", category.getName()));
    }

    // --- Items ----------------------------------------------------------------------------------

    /** The items, ordered by name; {@code categoryId} narrows to one category. */
    @Transactional(readOnly = true)
    public List<ItemResponse> items(UUID categoryId) {
        List<InventoryItem> items = categoryId == null ? itemRepository.findAllByOrderByName()
                : itemRepository.findByCategoryIdOrderByName(categoryId);
        Map<UUID, String> categoryNames = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(InventoryCategory::getId, InventoryCategory::getName));
        return items.stream().map(i -> toResponse(i, categoryNames.get(i.getCategoryId()))).toList();
    }

    /** @throws ApiException 404 if the category doesn't exist, 409 if the category already has an item of that name. */
    @Transactional
    public ItemResponse createItem(ItemRequest request) {
        InventoryCategory category = requireCategory(request.categoryId());
        String name = request.name().trim();
        if (itemRepository.existsByNameIgnoreCaseAndCategoryId(name, category.getId())) {
            throw itemNameTaken(name, category);
        }
        InventoryItem item = new InventoryItem();
        item.setName(name);
        item.setCategoryId(category.getId());
        item.setUnit(request.unit().trim());
        item.setDescription(blankToNull(request.description()));
        InventoryItem saved = saveItem(item, name, category);
        auditService.log(AuditActions.INVENTORY_ITEM_CREATED, AuditActions.INVENTORY_ITEM, saved.getId(),
                Map.of("name", name, "unit", saved.getUnit()));
        return toResponse(saved, category.getName());
    }

    /** @throws ApiException 404 if no such item or category, 409 if the category already has another item of that name. */
    @Transactional
    public ItemResponse updateItem(UUID id, ItemRequest request) {
        InventoryItem item = itemRepository.findForUpdate(id).orElseThrow(() -> new ApiException("Item not found", HttpStatus.NOT_FOUND));
        InventoryCategory category = requireCategory(request.categoryId());
        String name = request.name().trim();
        if (itemRepository.existsByNameIgnoreCaseAndCategoryIdAndIdNot(name, category.getId(), id)) {
            throw itemNameTaken(name, category);
        }
        item.setName(name);
        item.setCategoryId(category.getId());
        item.setUnit(request.unit().trim());
        item.setDescription(blankToNull(request.description()));
        InventoryItem saved = saveItem(item, name, category);
        auditService.log(AuditActions.INVENTORY_ITEM_UPDATED, AuditActions.INVENTORY_ITEM, id, Map.of("name", name, "unit", saved.getUnit()));
        return toResponse(saved, category.getName());
    }

    /** @throws ApiException 404 if no such item, 409 if it has been issued. */
    @Transactional
    public void deleteItem(UUID id) {
        InventoryItem item = itemRepository.findById(id).orElseThrow(() -> new ApiException("Item not found", HttpStatus.NOT_FOUND));
        if (issueRepository.existsByItemId(id)) {
            throw new ApiException("'" + item.getName() + "' has been issued and can't be deleted", HttpStatus.CONFLICT);
        }
        if (stockRepository.existsByItemId(id)) {
            throw new ApiException("'" + item.getName() + "' has stock entries and can't be deleted", HttpStatus.CONFLICT);
        }
        itemRepository.delete(item);
        auditService.log(AuditActions.INVENTORY_ITEM_DELETED, AuditActions.INVENTORY_ITEM, id, Map.of("name", item.getName()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private InventoryCategory requireCategory(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> new ApiException("Item category not found", HttpStatus.NOT_FOUND));
    }

    private InventoryCategory saveCategory(InventoryCategory category, String name) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            throw categoryNameTaken(name);
        }
    }

    private InventoryItem saveItem(InventoryItem item, String name, InventoryCategory category) {
        try {
            return itemRepository.saveAndFlush(item);
        } catch (DataIntegrityViolationException ex) {
            throw itemNameTaken(name, category);
        }
    }

    private static ItemResponse toResponse(InventoryItem item, String categoryName) {
        return new ItemResponse(item.getId(), item.getName(), item.getCategoryId(), categoryName, item.getUnit(), item.getDescription(),
                item.getStock());
    }

    private static ApiException categoryNameTaken(String name) {
        return new ApiException("An item category named '" + name + "' already exists", HttpStatus.CONFLICT);
    }

    private static ApiException itemNameTaken(String name, InventoryCategory category) {
        return new ApiException("'" + category.getName() + "' already has an item named '" + name + "'", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
