-- Durable saga state and compensation queue.
--
-- Before this migration, the saga had no record of its own progress. The order
-- row was mutated inside one long transaction that also made the remote calls,
-- so a crash mid-checkout rolled every local change back: nothing knew a
-- checkout had been in flight and nothing could compensate it.
--
-- The order is now persisted BEFORE the first remote call, which makes the
-- in-flight order (and its reservation/payment references) durable. These two
-- tables add the missing pieces:
--
--   saga_state          how far the orchestration got, and who is driving it
--   saga_compensations  undo work that must survive a crash and be retried

CREATE TABLE IF NOT EXISTS saga_state (
    order_id    VARCHAR(36) PRIMARY KEY,
    step        VARCHAR(24) NOT NULL,
    -- Claim lease. Only the holder may advance the saga; if the holder dies,
    -- claimed_at goes stale and a recovery sweep may take over (or fail it).
    claim_token VARCHAR(36),
    claimed_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_saga_state_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    -- Mirrors the SagaStep enum. Ordered with the checkout flow: a step may only
    -- move forward, and COMPLETED is the only terminal value.
    CONSTRAINT ck_saga_step CHECK (step IN
        ('STARTED','STOCK_RESERVED','PAYMENT_AUTHORISED','STOCK_CONFIRMED',
         'PAYMENT_CAPTURED','COMPLETED'))
);

CREATE INDEX IF NOT EXISTS idx_saga_state_stale
    ON saga_state (claimed_at) WHERE claim_token IS NOT NULL;

-- Compensating actions are rows, not log lines. A compensation that is only
-- attempted once, inline, is lost if the process dies or the payment service is
-- briefly unavailable - which is exactly when it matters.
--
-- (order_id, action) is unique so an orchestrator that retries a failure path
-- cannot queue the same undo twice.
CREATE TABLE IF NOT EXISTS saga_compensations (
    id              VARCHAR(36)   PRIMARY KEY,
    order_id        VARCHAR(36)   NOT NULL,
    action          VARCHAR(32)   NOT NULL,
    -- Reservation id for stock actions, payment id for money actions.
    target_ref      VARCHAR(128)  NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    -- Worker lease. The row is not picked up again until this passes, which is
    -- what stops two replicas retrying the same action at the same instant.
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error      VARCHAR(1024),
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_saga_compensation_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ux_saga_compensation UNIQUE (order_id, action),
    CONSTRAINT ck_saga_action CHECK (action IN
        ('RELEASE_STOCK','UNCONFIRM_STOCK','VOID_PAYMENT','REFUND_PAYMENT'))
);

-- The worker's hot query is always "unfinished, due now, oldest first".
CREATE INDEX IF NOT EXISTS idx_saga_compensation_due
    ON saga_compensations (next_attempt_at, id) WHERE completed_at IS NULL;
