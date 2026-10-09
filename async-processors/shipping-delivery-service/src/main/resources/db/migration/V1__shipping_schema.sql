-- Shipment lifecycle.

CREATE TABLE IF NOT EXISTS shipments (
    id                  VARCHAR(36)   PRIMARY KEY,
    order_id            VARCHAR(64)   NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    customer_id         VARCHAR(64)   NOT NULL,
    status              VARCHAR(24)   NOT NULL,
    carrier             VARCHAR(64),
    service_level       VARCHAR(64),
    tracking_number     VARCHAR(128),
    label_url           VARCHAR(512),
    shipping_address    VARCHAR(1024) NOT NULL,
    estimated_delivery  VARCHAR(64),
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_shipment_status CHECK (status IN
        ('PENDING','LABEL_CREATED','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED',
         'FAILED_ATTEMPT','RETURNED','CANCELLED'))
);

-- One shipment per order. This unique index is what makes a redelivered
-- order.paid event idempotent rather than buying a second label.
CREATE UNIQUE INDEX IF NOT EXISTS ux_shipment_order
    ON shipments (order_id, tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_shipment_tracking
    ON shipments (tracking_number) WHERE tracking_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_shipment_order  ON shipments (order_id);
CREATE INDEX IF NOT EXISTS idx_shipment_tenant_status ON shipments (tenant_id, status);
