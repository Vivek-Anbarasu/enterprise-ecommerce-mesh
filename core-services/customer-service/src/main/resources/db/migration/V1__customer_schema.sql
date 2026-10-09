-- Customer profile and address book.

CREATE TABLE IF NOT EXISTS customers (
    id               VARCHAR(36)   PRIMARY KEY,
    subject          VARCHAR(128)  NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL,
    email            VARCHAR(320)  NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    phone            VARCHAR(32),
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    marketing_opt_in BOOLEAN       NOT NULL DEFAULT FALSE,
    locale           VARCHAR(16),
    timezone         VARCHAR(64),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE','DEACTIVATED','SUSPENDED'))
);

-- One account per identity-provider subject per tenant. A shopper changing their
-- email must keep a single account rather than silently gaining a second one.
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_tenant_subject
    ON customers (tenant_id, subject);
CREATE INDEX IF NOT EXISTS idx_customer_tenant_email
    ON customers (tenant_id, lower(email));
CREATE INDEX IF NOT EXISTS idx_customer_tenant
    ON customers (tenant_id);

CREATE TABLE IF NOT EXISTS customer_addresses (
    id             BIGSERIAL      PRIMARY KEY,
    customer_id    VARCHAR(36)    NOT NULL,
    label          VARCHAR(64),
    recipient_name VARCHAR(128)   NOT NULL,
    line1          VARCHAR(256)   NOT NULL,
    line2          VARCHAR(256),
    city           VARCHAR(128)   NOT NULL,
    state          VARCHAR(128),
    postal_code    VARCHAR(32)    NOT NULL,
    country        VARCHAR(2)     NOT NULL,
    is_default     BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_address_customer
        FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_address_customer ON customer_addresses (customer_id);

-- At most one default address per customer, enforced in the database rather
-- than only in application code.
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_single_default_address
    ON customer_addresses (customer_id) WHERE is_default;
