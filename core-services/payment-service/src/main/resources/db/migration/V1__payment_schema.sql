-- Payment ledger.

CREATE TABLE IF NOT EXISTS payments (
    id                  VARCHAR(36)   PRIMARY KEY,
    idempotency_key     VARCHAR(64)   NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    order_id            VARCHAR(64)   NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    provider_reference  VARCHAR(128),
    -- Masked label only ("Visa ****4242"). No PAN is ever stored: this table is
    -- in PCI scope, and a raw card number here would breach it.
    instrument_label    VARCHAR(64),
    decline_reason      VARCHAR(256),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_status CHECK (status IN
        ('AUTHORISED','CAPTURED','VOIDED','REFUNDED','DECLINED')),
    CONSTRAINT ck_payment_amount_positive CHECK (amount > 0)
);

-- The idempotency guarantee. A retried authorisation must replay the stored
-- result rather than charging the customer twice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_idempotency
    ON payments (tenant_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payment_order ON payments (order_id);
CREATE INDEX IF NOT EXISTS idx_payment_tenant_order ON payments (tenant_id, order_id);

-- Append-only audit of every state change. Ledger tables must never be updated
-- in place: the audit trail is what reconciliation and disputes rely on.
CREATE TABLE IF NOT EXISTS payment_audit (
    id         BIGSERIAL   PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16) NOT NULL,
    amount      NUMERIC(19,4),
    reason      VARCHAR(256),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_audit_payment ON payment_audit (payment_id, recorded_at);
