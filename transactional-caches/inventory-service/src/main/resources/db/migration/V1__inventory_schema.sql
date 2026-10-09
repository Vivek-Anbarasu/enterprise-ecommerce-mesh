-- Inventory schema.
--
-- Note on money/quantity types: stock is an integer count and is deliberately
-- not BIGINT-overflow-prone here, but CHECK constraints enforce non-negativity
-- so a bug cannot silently create phantom stock.

CREATE TABLE IF NOT EXISTS inventory_items (
    id             VARCHAR(64)   PRIMARY KEY,
    sku            VARCHAR(64)   NOT NULL,
    tenant_id      VARCHAR(64)   NOT NULL,
    location_code  VARCHAR(32)   NOT NULL,
    on_hand        INTEGER       NOT NULL DEFAULT 0,
    reserved       INTEGER       NOT NULL DEFAULT 0,
    safety_stock   INTEGER       NOT NULL DEFAULT 0,
    version        BIGINT        NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_on_hand_non_negative   CHECK (on_hand >= 0),
    CONSTRAINT ck_inventory_reserved_non_negative  CHECK (reserved >= 0),
    CONSTRAINT ck_inventory_safety_non_negative    CHECK (safety_stock >= 0),
    -- Availability must never be negative; enforced in the DB as the last line
    -- of defence behind the service-level check.
    CONSTRAINT ck_inventory_not_oversold           CHECK (reserved <= on_hand)
);

-- One stock row per SKU/location/tenant.
CREATE UNIQUE INDEX IF NOT EXISTS ux_inventory_sku_tenant_location
    ON inventory_items (sku, tenant_id, location_code);
CREATE INDEX IF NOT EXISTS idx_inventory_sku_tenant
    ON inventory_items (sku, tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_tenant
    ON inventory_items (tenant_id);

CREATE TABLE IF NOT EXISTS inventory_reservations (
    reservation_id VARCHAR(64)   PRIMARY KEY,
    sku            VARCHAR(64)   NOT NULL,
    tenant_id      VARCHAR(64)   NOT NULL,
    order_id       VARCHAR(64),
    quantity       INTEGER       NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    expires_at     TIMESTAMPTZ   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_reservation_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservation_status CHECK (status IN ('PENDING','CONFIRMED','RELEASED','EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_reservation_order ON inventory_reservations (order_id);
-- Drives the expiry sweeper's hot query.
CREATE INDEX IF NOT EXISTS idx_reservation_expiry ON inventory_reservations (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_reservation_sku    ON inventory_reservations (sku, tenant_id);

-- Per-SKU line detail. Without this, confirm/release could not tell which SKUs a
-- multi-line reservation actually covered.
CREATE TABLE IF NOT EXISTS inventory_reservation_lines (
    reservation_id VARCHAR(64) NOT NULL,
    sku            VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL,
    PRIMARY KEY (reservation_id, sku),
    CONSTRAINT fk_res_line_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservations (reservation_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_res_line_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_res_line_reservation ON inventory_reservation_lines (reservation_id);
CREATE INDEX IF NOT EXISTS idx_res_line_sku         ON inventory_reservation_lines (sku);
