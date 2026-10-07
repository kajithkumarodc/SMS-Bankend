-- Inventory: stores, suppliers and the stock entries (units bought in or taken out of an item).

CREATE TABLE inventory_stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    code VARCHAR(30),
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_stores_name_key UNIQUE (name)
);

CREATE TABLE inventory_suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(30),
    email VARCHAR(150),
    address VARCHAR(300),
    contact_person_name VARCHAR(100),
    contact_person_phone VARCHAR(30),
    contact_person_email VARCHAR(150),
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_suppliers_name_key UNIQUE (name)
);

CREATE TABLE inventory_stock_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id UUID NOT NULL REFERENCES inventory_items(id),
    supplier_id UUID REFERENCES inventory_suppliers(id),
    store_id UUID REFERENCES inventory_stores(id),
    -- Units added to the item's stock; negative when units are taken out (damaged, lost, sent back).
    quantity INTEGER NOT NULL,
    purchase_price NUMERIC(12, 2) NOT NULL,
    entry_date DATE NOT NULL,
    description VARCHAR(500),
    attachment_original_filename VARCHAR(255),
    attachment_stored_filename VARCHAR(100),
    attachment_content_type VARCHAR(150),
    attachment_size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_stock_entries_quantity_check CHECK (quantity <> 0),
    CONSTRAINT inventory_stock_entries_price_check CHECK (purchase_price >= 0),
    CONSTRAINT inventory_stock_entries_attachment_check CHECK (
        (attachment_stored_filename IS NULL) = (attachment_original_filename IS NULL))
);

CREATE INDEX inventory_stock_entries_date_idx ON inventory_stock_entries(entry_date DESC);
CREATE INDEX inventory_stock_entries_item_idx ON inventory_stock_entries(item_id);
