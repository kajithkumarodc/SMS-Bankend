package com.smsapp.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Inventory API. */
final class InventoryDtos {

    private InventoryDtos() {
    }

    record CategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    record CategoryResponse(UUID id, String name) {
        static CategoryResponse from(InventoryCategory category) {
            return new CategoryResponse(category.getId(), category.getName());
        }
    }

    record ItemRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull UUID categoryId,
            @NotNull @Min(0) @Max(1_000_000) Integer stock) {
    }

    record ItemResponse(UUID id, String name, UUID categoryId, String categoryName, int stock) {
    }

    /** Body for {@code POST /api/v1/inventory/issues}: the Issue Item form. */
    record IssueRequest(
            @NotNull UUID issueToStaffId,
            @NotNull UUID issuedByStaffId,
            @NotNull LocalDate issueDate,
            LocalDate returnDate,
            @Size(max = 500) String note,
            @NotNull UUID itemId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
    }

    record IssueResponse(
            UUID id,
            UUID itemId,
            String itemName,
            String categoryName,
            String note,
            LocalDate issueDate,
            LocalDate returnDate,
            UUID issueToStaffId,
            String issueToName,
            String issueToCode,
            UUID issuedByStaffId,
            String issuedByName,
            String issuedByCode,
            int quantity,
            /** ISSUED or RETURNED. */
            String status,
            OffsetDateTime returnedAt) {
    }

    record StoreRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String code,
            @Size(max = 500) String description) {
    }

    record StoreResponse(UUID id, String name, String code, String description) {
        static StoreResponse from(InventoryStore store) {
            return new StoreResponse(store.getId(), store.getName(), store.getCode(), store.getDescription());
        }
    }

    record SupplierRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String phone,
            @Size(max = 150) @Email String email,
            @Size(max = 300) String address,
            @Size(max = 100) String contactPersonName,
            @Size(max = 30) String contactPersonPhone,
            @Size(max = 150) @Email String contactPersonEmail,
            @Size(max = 500) String description) {
    }

    record SupplierResponse(UUID id, String name, String phone, String email, String address, String contactPersonName,
                            String contactPersonPhone, String contactPersonEmail, String description) {
        static SupplierResponse from(InventorySupplier s) {
            return new SupplierResponse(s.getId(), s.getName(), s.getPhone(), s.getEmail(), s.getAddress(), s.getContactPersonName(),
                    s.getContactPersonPhone(), s.getContactPersonEmail(), s.getDescription());
        }
    }

    record AttachmentInfo(String fileName, String contentType, long sizeBytes) {
    }

    /** The Add Item Stock form. {@code quantity} is negative to take units out of stock. */
    record StockEntryRequest(
            @NotNull UUID itemId,
            UUID supplierId,
            UUID storeId,
            @NotNull @Min(-1_000_000) @Max(1_000_000) Integer quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal purchasePrice,
            @NotNull LocalDate date,
            @Size(max = 500) String description) {
    }

    record StockEntryResponse(
            UUID id,
            UUID itemId,
            String itemName,
            UUID categoryId,
            String categoryName,
            UUID supplierId,
            String supplierName,
            UUID storeId,
            /** "Name (code)". */
            String storeName,
            int quantity,
            BigDecimal purchasePrice,
            LocalDate date,
            String description,
            AttachmentInfo attachment) {
    }
}
