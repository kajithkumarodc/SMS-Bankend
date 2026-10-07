-- Inventory item categories get an optional description.

ALTER TABLE inventory_categories ADD COLUMN description VARCHAR(500);
