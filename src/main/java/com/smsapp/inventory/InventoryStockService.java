package com.smsapp.inventory;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.FrontOfficeFileStore;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import com.smsapp.inventory.InventoryDtos.AttachmentInfo;
import com.smsapp.inventory.InventoryDtos.StockEntryRequest;
import com.smsapp.inventory.InventoryDtos.StockEntryResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inventory > Add Item Stock: every purchase (or write-off) of an item. An entry adds its quantity to the item's
 * stock (a negative quantity takes units out); changing or deleting an entry reverses its effect first. Stock never
 * goes below zero.
 */
@Service
public class InventoryStockService {

    static final String STORAGE_AREA = "inventory-stock";

    private final InventoryStockEntryRepository stockRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryCategoryRepository categoryRepository;
    private final InventorySupplierRepository supplierRepository;
    private final InventoryStoreRepository storeRepository;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public InventoryStockService(InventoryStockEntryRepository stockRepository, InventoryItemRepository itemRepository,
                                 InventoryCategoryRepository categoryRepository, InventorySupplierRepository supplierRepository,
                                 InventoryStoreRepository storeRepository, FrontOfficeFileStore fileStore, AuditService auditService) {
        this.stockRepository = stockRepository;
        this.itemRepository = itemRepository;
        this.categoryRepository = categoryRepository;
        this.supplierRepository = supplierRepository;
        this.storeRepository = storeRepository;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** Every stock entry, latest date first. */
    @Transactional(readOnly = true)
    public List<StockEntryResponse> list() {
        return toResponses(stockRepository.findAllByOrderByEntryDateDescCreatedAtDesc());
    }

    /** @throws ApiException 404 if the item, supplier or store doesn't exist, 409 if a negative quantity is more than is in stock. */
    @Transactional
    public StockEntryResponse create(StockEntryRequest request) {
        requireReferences(request);
        InventoryItem item = lockItem(request.itemId());
        changeStock(item, request.quantity());
        InventoryStockEntry entry = new InventoryStockEntry();
        apply(entry, request);
        InventoryStockEntry saved = stockRepository.save(entry);
        auditService.log(AuditActions.INVENTORY_STOCK_ADDED, AuditActions.INVENTORY_STOCK, saved.getId(),
                Map.of("item", item.getName(), "quantity", saved.getQuantity()));
        return toResponses(List.of(saved)).get(0);
    }

    /** Replaces an entry: its old quantity is taken off its old item and the new one put on the (possibly other) item. */
    @Transactional
    public StockEntryResponse update(UUID id, StockEntryRequest request) {
        InventoryStockEntry entry = require(id);
        requireReferences(request);
        // Lock in a stable order so two edits touching the same two items can't deadlock.
        InventoryItem oldItem;
        InventoryItem newItem;
        if (entry.getItemId().equals(request.itemId())) {
            oldItem = lockItem(entry.getItemId());
            newItem = oldItem;
        } else if (entry.getItemId().compareTo(request.itemId()) < 0) {
            oldItem = lockItem(entry.getItemId());
            newItem = lockItem(request.itemId());
        } else {
            newItem = lockItem(request.itemId());
            oldItem = lockItem(entry.getItemId());
        }
        changeStock(oldItem, -entry.getQuantity());
        changeStock(newItem, request.quantity());
        apply(entry, request);
        InventoryStockEntry saved = stockRepository.save(entry);
        auditService.log(AuditActions.INVENTORY_STOCK_UPDATED, AuditActions.INVENTORY_STOCK, id,
                Map.of("item", newItem.getName(), "quantity", saved.getQuantity()));
        return toResponses(List.of(saved)).get(0);
    }

    /** Deletes an entry and takes its quantity back off the item. @throws ApiException 409 if those units are already issued. */
    @Transactional
    public void delete(UUID id) {
        InventoryStockEntry entry = require(id);
        InventoryItem item = lockItem(entry.getItemId());
        changeStock(item, -entry.getQuantity());
        String storedFile = entry.getAttachmentStoredFilename();
        stockRepository.delete(entry);
        afterCommit(() -> {
            if (storedFile != null) {
                fileStore.delete(STORAGE_AREA, id, storedFile);
            }
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.INVENTORY_STOCK_DELETED, AuditActions.INVENTORY_STOCK, id,
                Map.of("item", item.getName(), "quantity", entry.getQuantity()));
    }

    // --- Document -------------------------------------------------------------------------------

    /** @throws ApiException 404 if no such entry, 400 if the file is empty, over 10 MB or not an allowed type. */
    @Transactional
    public StockEntryResponse attach(UUID id, MultipartFile file) {
        InventoryStockEntry entry = require(id);
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        String previous = entry.getAttachmentStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        entry.setAttachmentOriginalFilename(stored.originalFilename());
        entry.setAttachmentStoredFilename(stored.storedFilename());
        entry.setAttachmentContentType(stored.contentType());
        entry.setAttachmentSizeBytes(stored.sizeBytes());
        return toResponses(List.of(stockRepository.save(entry))).get(0);
    }

    /** @throws ApiException 404 if no such entry or it has no document. */
    @Transactional
    public StockEntryResponse removeAttachment(UUID id) {
        InventoryStockEntry entry = requireWithAttachment(id);
        String storedFile = entry.getAttachmentStoredFilename();
        entry.setAttachmentOriginalFilename(null);
        entry.setAttachmentStoredFilename(null);
        entry.setAttachmentContentType(null);
        entry.setAttachmentSizeBytes(null);
        InventoryStockEntry saved = stockRepository.save(entry);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        return toResponses(List.of(saved)).get(0);
    }

    /** @throws ApiException 404 if no such entry or it has no document. */
    @Transactional(readOnly = true)
    public InventoryStockEntry requireWithAttachment(UUID id) {
        InventoryStockEntry entry = require(id);
        if (!entry.hasAttachment()) {
            throw new ApiException("This stock entry has no attached document", HttpStatus.NOT_FOUND);
        }
        return entry;
    }

    public Resource loadAttachment(InventoryStockEntry entry) {
        return fileStore.load(STORAGE_AREA, entry.getId(), entry.getAttachmentStoredFilename());
    }

    // --- Helpers --------------------------------------------------------------------------------

    private InventoryStockEntry require(UUID id) {
        return stockRepository.findById(id).orElseThrow(() -> new ApiException("Stock entry not found", HttpStatus.NOT_FOUND));
    }

    private InventoryItem lockItem(UUID id) {
        return itemRepository.findForUpdate(id).orElseThrow(() -> new ApiException("Item not found", HttpStatus.NOT_FOUND));
    }

    private void changeStock(InventoryItem item, int delta) {
        long next = (long) item.getStock() + delta;
        if (next < 0) {
            throw new ApiException("'" + item.getName() + "' has only " + item.getStock() + " in stock, so "
                    + Math.abs(delta) + " can't be taken out", HttpStatus.CONFLICT);
        }
        item.setStock((int) next);
        itemRepository.save(item);
    }

    private void requireReferences(StockEntryRequest request) {
        if (request.quantity() == 0) {
            throw new ApiException("Quantity can't be 0", HttpStatus.BAD_REQUEST);
        }
        if (request.supplierId() != null && !supplierRepository.existsById(request.supplierId())) {
            throw new ApiException("Item supplier not found", HttpStatus.NOT_FOUND);
        }
        if (request.storeId() != null && !storeRepository.existsById(request.storeId())) {
            throw new ApiException("Item store not found", HttpStatus.NOT_FOUND);
        }
    }

    private static void apply(InventoryStockEntry entry, StockEntryRequest request) {
        entry.setItemId(request.itemId());
        entry.setSupplierId(request.supplierId());
        entry.setStoreId(request.storeId());
        entry.setQuantity(request.quantity());
        entry.setPurchasePrice(request.purchasePrice());
        entry.setEntryDate(request.date());
        String description = request.description() == null ? null : request.description().trim();
        entry.setDescription(description == null || description.isEmpty() ? null : description);
    }

    private List<StockEntryResponse> toResponses(List<InventoryStockEntry> entries) {
        Map<UUID, InventoryItem> items = itemRepository.findAll().stream().collect(Collectors.toMap(InventoryItem::getId, Function.identity()));
        Map<UUID, String> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(InventoryCategory::getId, InventoryCategory::getName));
        Map<UUID, String> suppliers = supplierRepository.findAll().stream()
                .collect(Collectors.toMap(InventorySupplier::getId, InventorySupplier::getName));
        Map<UUID, InventoryStore> stores = storeRepository.findAll().stream().collect(Collectors.toMap(InventoryStore::getId, Function.identity()));
        return entries.stream().map(e -> {
            InventoryItem item = items.get(e.getItemId());
            InventoryStore store = e.getStoreId() == null ? null : stores.get(e.getStoreId());
            AttachmentInfo attachment = e.hasAttachment() ? new AttachmentInfo(e.getAttachmentOriginalFilename(),
                    e.getAttachmentContentType(), e.getAttachmentSizeBytes() == null ? 0 : e.getAttachmentSizeBytes()) : null;
            return new StockEntryResponse(e.getId(), e.getItemId(), item == null ? null : item.getName(),
                    item == null ? null : item.getCategoryId(), item == null ? null : categories.get(item.getCategoryId()),
                    e.getSupplierId(), e.getSupplierId() == null ? null : suppliers.get(e.getSupplierId()), e.getStoreId(),
                    store == null ? null : store.getName() + (store.getCode() == null ? "" : " (" + store.getCode() + ")"),
                    e.getQuantity(), e.getPurchasePrice(), e.getEntryDate(), e.getDescription(), attachment);
        }).toList();
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
}
