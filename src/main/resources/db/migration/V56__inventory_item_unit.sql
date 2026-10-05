-- Inventory items get a unit (Piece, Box, ...) and a description. The available quantity now comes only from stock
-- entries (Add Item Stock) and issues, so existing items keep their current stock.

ALTER TABLE inventory_items
    ADD COLUMN unit VARCHAR(30) NOT NULL DEFAULT 'Piece',
    ADD COLUMN description VARCHAR(500);
