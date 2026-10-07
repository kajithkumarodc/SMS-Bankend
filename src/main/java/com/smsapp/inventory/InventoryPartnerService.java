package com.smsapp.inventory;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.inventory.InventoryDtos.StoreRequest;
import com.smsapp.inventory.InventoryDtos.SupplierRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory > Item Store and Item Supplier: where items are kept and who they are bought from. Names are unique
 * ignoring case; a store or supplier that has stock entries cannot be deleted.
 */
@Service
public class InventoryPartnerService {

    private final InventoryStoreRepository storeRepository;
    private final InventorySupplierRepository supplierRepository;
    private final InventoryStockEntryRepository stockRepository;
    private final AuditService auditService;

    public InventoryPartnerService(InventoryStoreRepository storeRepository, InventorySupplierRepository supplierRepository,
                                   InventoryStockEntryRepository stockRepository, AuditService auditService) {
        this.storeRepository = storeRepository;
        this.supplierRepository = supplierRepository;
        this.stockRepository = stockRepository;
        this.auditService = auditService;
    }

    // --- Stores ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryStore> stores() {
        return storeRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a store with that name already exists. */
    @Transactional
    public InventoryStore createStore(StoreRequest request) {
        String name = request.name().trim();
        if (storeRepository.existsByNameIgnoreCase(name)) {
            throw storeNameTaken(name);
        }
        InventoryStore store = new InventoryStore();
        apply(store, request);
        InventoryStore saved = saveStore(store, name);
        auditService.log(AuditActions.INVENTORY_STORE_CREATED, AuditActions.INVENTORY_STORE, saved.getId(), Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such store, 409 if another one already has that name. */
    @Transactional
    public InventoryStore updateStore(UUID id, StoreRequest request) {
        InventoryStore store = requireStore(id);
        String name = request.name().trim();
        if (storeRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw storeNameTaken(name);
        }
        apply(store, request);
        InventoryStore saved = saveStore(store, name);
        auditService.log(AuditActions.INVENTORY_STORE_UPDATED, AuditActions.INVENTORY_STORE, id, Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such store, 409 if stock entries use it. */
    @Transactional
    public void deleteStore(UUID id) {
        InventoryStore store = requireStore(id);
        if (stockRepository.existsByStoreId(id)) {
            throw new ApiException("'" + store.getName() + "' has stock entries and can't be deleted", HttpStatus.CONFLICT);
        }
        storeRepository.delete(store);
        auditService.log(AuditActions.INVENTORY_STORE_DELETED, AuditActions.INVENTORY_STORE, id, Map.of("name", store.getName()));
    }

    // --- Suppliers ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventorySupplier> suppliers() {
        return supplierRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a supplier with that name already exists. */
    @Transactional
    public InventorySupplier createSupplier(SupplierRequest request) {
        String name = request.name().trim();
        if (supplierRepository.existsByNameIgnoreCase(name)) {
            throw supplierNameTaken(name);
        }
        InventorySupplier supplier = new InventorySupplier();
        apply(supplier, request);
        InventorySupplier saved = saveSupplier(supplier, name);
        auditService.log(AuditActions.INVENTORY_SUPPLIER_CREATED, AuditActions.INVENTORY_SUPPLIER, saved.getId(), Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such supplier, 409 if another one already has that name. */
    @Transactional
    public InventorySupplier updateSupplier(UUID id, SupplierRequest request) {
        InventorySupplier supplier = requireSupplier(id);
        String name = request.name().trim();
        if (supplierRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw supplierNameTaken(name);
        }
        apply(supplier, request);
        InventorySupplier saved = saveSupplier(supplier, name);
        auditService.log(AuditActions.INVENTORY_SUPPLIER_UPDATED, AuditActions.INVENTORY_SUPPLIER, id, Map.of("name", name));
        return saved;
    }

    /** @throws ApiException 404 if no such supplier, 409 if stock entries use it. */
    @Transactional
    public void deleteSupplier(UUID id) {
        InventorySupplier supplier = requireSupplier(id);
        if (stockRepository.existsBySupplierId(id)) {
            throw new ApiException("'" + supplier.getName() + "' has stock entries and can't be deleted", HttpStatus.CONFLICT);
        }
        supplierRepository.delete(supplier);
        auditService.log(AuditActions.INVENTORY_SUPPLIER_DELETED, AuditActions.INVENTORY_SUPPLIER, id, Map.of("name", supplier.getName()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private InventoryStore requireStore(UUID id) {
        return storeRepository.findById(id).orElseThrow(() -> new ApiException("Item store not found", HttpStatus.NOT_FOUND));
    }

    private InventorySupplier requireSupplier(UUID id) {
        return supplierRepository.findById(id).orElseThrow(() -> new ApiException("Item supplier not found", HttpStatus.NOT_FOUND));
    }

    private InventoryStore saveStore(InventoryStore store, String name) {
        try {
            return storeRepository.saveAndFlush(store);
        } catch (DataIntegrityViolationException ex) {
            throw storeNameTaken(name);
        }
    }

    private InventorySupplier saveSupplier(InventorySupplier supplier, String name) {
        try {
            return supplierRepository.saveAndFlush(supplier);
        } catch (DataIntegrityViolationException ex) {
            throw supplierNameTaken(name);
        }
    }

    private static void apply(InventoryStore store, StoreRequest request) {
        store.setName(request.name().trim());
        store.setCode(blankToNull(request.code()));
        store.setDescription(blankToNull(request.description()));
    }

    private static void apply(InventorySupplier supplier, SupplierRequest request) {
        supplier.setName(request.name().trim());
        supplier.setPhone(blankToNull(request.phone()));
        supplier.setEmail(blankToNull(request.email()));
        supplier.setAddress(blankToNull(request.address()));
        supplier.setContactPersonName(blankToNull(request.contactPersonName()));
        supplier.setContactPersonPhone(blankToNull(request.contactPersonPhone()));
        supplier.setContactPersonEmail(blankToNull(request.contactPersonEmail()));
        supplier.setDescription(blankToNull(request.description()));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException storeNameTaken(String name) {
        return new ApiException("An item store named '" + name + "' already exists", HttpStatus.CONFLICT);
    }

    private static ApiException supplierNameTaken(String name) {
        return new ApiException("An item supplier named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
