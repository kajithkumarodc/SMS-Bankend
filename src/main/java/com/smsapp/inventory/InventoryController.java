package com.smsapp.inventory;

import com.smsapp.inventory.InventoryDtos.CategoryRequest;
import com.smsapp.inventory.InventoryDtos.CategoryResponse;
import com.smsapp.inventory.InventoryDtos.IssueRequest;
import com.smsapp.inventory.InventoryDtos.IssueResponse;
import com.smsapp.inventory.InventoryDtos.ItemRequest;
import com.smsapp.inventory.InventoryDtos.ItemResponse;
import com.smsapp.inventory.InventoryDtos.StockEntryRequest;
import com.smsapp.inventory.InventoryDtos.StockEntryResponse;
import com.smsapp.inventory.InventoryDtos.StoreRequest;
import com.smsapp.inventory.InventoryDtos.StoreResponse;
import com.smsapp.inventory.InventoryDtos.SupplierRequest;
import com.smsapp.inventory.InventoryDtos.SupplierResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Inventory: INVENTORY_VIEW reads categories, items and issues; INVENTORY_ISSUE issues, returns and deletes issues;
 * INVENTORY_MANAGE changes categories and items.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryCatalogService catalog;
    private final InventoryIssueService issues;
    private final InventoryPartnerService partners;
    private final InventoryStockService stock;

    public InventoryController(InventoryCatalogService catalog, InventoryIssueService issues, InventoryPartnerService partners,
                               InventoryStockService stock) {
        this.catalog = catalog;
        this.issues = issues;
        this.partners = partners;
        this.stock = stock;
    }

    // --- Categories -----------------------------------------------------------------------------

    @GetMapping("/categories")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<CategoryResponse> categories() {
        return catalog.categories().stream().map(CategoryResponse::from).toList();
    }

    @PostMapping("/categories")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(catalog.createCategory(request.name(), request.description())));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    CategoryResponse renameCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.from(catalog.updateCategory(id, request.name(), request.description()));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        catalog.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    // --- Items ----------------------------------------------------------------------------------

    @GetMapping("/items")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<ItemResponse> items(@RequestParam(required = false) UUID categoryId) {
        return catalog.items(categoryId);
    }

    @PostMapping("/items")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<ItemResponse> createItem(@Valid @RequestBody ItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createItem(request));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ItemResponse updateItem(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return catalog.updateItem(id, request);
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<Void> deleteItem(@PathVariable UUID id) {
        catalog.deleteItem(id);
        return ResponseEntity.noContent().build();
    }

    // --- Issues ---------------------------------------------------------------------------------

    @GetMapping("/issues")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<IssueResponse> issues() {
        return issues.list();
    }

    @PostMapping("/issues")
    @PreAuthorize(Permissions.HAS_INVENTORY_ISSUE)
    ResponseEntity<IssueResponse> issue(@Valid @RequestBody IssueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issues.issue(request));
    }

    @PostMapping("/issues/{id}/return")
    @PreAuthorize(Permissions.HAS_INVENTORY_ISSUE)
    IssueResponse returnIssue(@PathVariable UUID id) {
        return issues.returnIssue(id);
    }

    @DeleteMapping("/issues/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_ISSUE)
    ResponseEntity<Void> deleteIssue(@PathVariable UUID id) {
        issues.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Stores ---------------------------------------------------------------------------------

    @GetMapping("/stores")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<StoreResponse> stores() {
        return partners.stores().stream().map(StoreResponse::from).toList();
    }

    @PostMapping("/stores")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<StoreResponse> createStore(@Valid @RequestBody StoreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(StoreResponse.from(partners.createStore(request)));
    }

    @PutMapping("/stores/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    StoreResponse updateStore(@PathVariable UUID id, @Valid @RequestBody StoreRequest request) {
        return StoreResponse.from(partners.updateStore(id, request));
    }

    @DeleteMapping("/stores/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<Void> deleteStore(@PathVariable UUID id) {
        partners.deleteStore(id);
        return ResponseEntity.noContent().build();
    }

    // --- Suppliers ------------------------------------------------------------------------------

    @GetMapping("/suppliers")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<SupplierResponse> suppliers() {
        return partners.suppliers().stream().map(SupplierResponse::from).toList();
    }

    @PostMapping("/suppliers")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<SupplierResponse> createSupplier(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(SupplierResponse.from(partners.createSupplier(request)));
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    SupplierResponse updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(partners.updateSupplier(id, request));
    }

    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<Void> deleteSupplier(@PathVariable UUID id) {
        partners.deleteSupplier(id);
        return ResponseEntity.noContent().build();
    }

    // --- Stock entries --------------------------------------------------------------------------

    @GetMapping("/stock")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    List<StockEntryResponse> stock() {
        return stock.list();
    }

    @PostMapping("/stock")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<StockEntryResponse> addStock(@Valid @RequestBody StockEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.create(request));
    }

    @PutMapping("/stock/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    StockEntryResponse updateStock(@PathVariable UUID id, @Valid @RequestBody StockEntryRequest request) {
        return stock.update(id, request);
    }

    @DeleteMapping("/stock/{id}")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    ResponseEntity<Void> deleteStock(@PathVariable UUID id) {
        stock.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/stock/{id}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    StockEntryResponse attachStockDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return stock.attach(id, file);
    }

    @DeleteMapping("/stock/{id}/document")
    @PreAuthorize(Permissions.HAS_INVENTORY_MANAGE)
    StockEntryResponse removeStockDocument(@PathVariable UUID id) {
        return stock.removeAttachment(id);
    }

    @GetMapping("/stock/{id}/document")
    @PreAuthorize(Permissions.HAS_INVENTORY_VIEW)
    ResponseEntity<Resource> downloadStockDocument(@PathVariable UUID id) {
        InventoryStockEntry entry = stock.requireWithAttachment(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entry.getAttachmentContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(entry.getAttachmentOriginalFilename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(stock.loadAttachment(entry));
    }
}
