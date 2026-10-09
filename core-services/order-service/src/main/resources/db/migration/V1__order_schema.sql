-- Order schema plus the transactional outbox.

CREATE TABLE IF NOT EXISTS orders (
    id                       VARCHAR(36)   PRIMARY KEY,
    idempotency_key          VARCHAR(64)   NOT NULL,
    tenant_id                VARCHAR(64)   NOT NULL,
    customer_id              VARCHAR(64)   NOT NULL,
    inventory_reservation_id VARCHAR(64),
    status                   VARCHAR(16)   NOT NULL,
    total_amount             NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'USD',
    tax_amount               NUMERIC(19,4) NOT NULL DEFAULT 0,
    shipping_amount          NUMERIC(19,4) NOT NULL DEFAULT 0,
    payment_reference        VARCHAR(128),
    shipping_address         VARCHAR(1024) NOT NULL,
    correlation_id           VARCHAR(64),
    failure_reason           VARCHAR(512),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_order_status CHECK (status IN
        ('PENDING','RESERVED','PAID','SHIPPED','DELIVERED','CANCELLED','REFUNDED','FAILED')),
    CONSTRAINT ck_order_total_non_negative CHECK (total_amount >= 0)
);

-- The idempotency guarantee. Without this unique index, two concurrent retries
-- of the same checkout would both pass the lookup and create duplicate orders.
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_idempotency
    ON orders (tenant_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (tenant_id, customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_status   ON orders (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_reservation ON orders (inventory_reservation_id);

CREATE TABLE IF NOT EXISTS order_lines (
    id           BIGSERIAL     PRIMARY KEY,
    order_id     VARCHAR(36)   NOT NULL,
    sku          VARCHAR(64)   NOT NULL,
    product_name VARCHAR(256)  NOT NULL,
    quantity     INTEGER       NOT NULL,
    unit_price   NUMERIC(19,4) NOT NULL,
    line_total   NUMERIC(19,4) NOT NULL,
    CONSTRAINT fk_order_line_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_line_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_line_total_non_negative  CHECK (line_total >= 0)
);

CREATE INDEX IF NOT EXISTS idx_order_lines_order ON order_lines (order_id);
CREATE INDEX IF NOT EXISTS idx_order_lines_sku   ON order_lines (sku);

-- Transactional outbox. Rows are written in the same transaction as the order,
-- then relayed to Kafka and marked published.
CREATE TABLE IF NOT EXISTS outbox_events (
    id             VARCHAR(36)   PRIMARY KEY,
    event_id       VARCHAR(36)   NOT NULL,
    topic          VARCHAR(128)  NOT NULL,
    event_type     VARCHAR(128)  NOT NULL,
    aggregate_type VARCHAR(64)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    tenant_id      VARCHAR(64)   NOT NULL,
    schema_version INTEGER       NOT NULL DEFAULT 1,
    correlation_id VARCHAR(64),
    payload        JSONB         NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    last_error     VARCHAR(1024),
    -- The relay relies on this being unique so a redelivery can be detected.
    CONSTRAINT ux_outbox_event_id UNIQUE (event_id)
);

-- Partial index: the relay's hot query is always "unpublished, oldest first".
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events (created_at, id) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_events (aggregate_id);
