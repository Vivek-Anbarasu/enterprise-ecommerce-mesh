-- Allow a confirmed reservation to be undone.
--
-- order-service confirms stock in Step 3 of the checkout saga and only then
-- persists the order and its outbox event (Step 4). If Step 4 fails, the order
-- transaction rolls back and no order will ever reference that confirmed hold.
-- The previous release endpoint could not clean that up: it is guarded on
-- isPending(), so releasing a CONFIRMED reservation was a silent no-op, and the
-- sweeper ignores non-PENDING rows. Stock was therefore permanently decremented
-- for an order that did not exist.
--
-- UNCONFIRMED is the compensation for exactly that case. It reverses the
-- onHand decrement confirm applied and puts the units back on sale.
--
-- It is a distinct terminal state rather than reusing RELEASED so an operator can
-- tell "never confirmed, hold returned" apart from "confirmed then rolled back".
-- That distinction is the whole signal that a compensating action ran, so it must
-- survive in the ledger.
--
-- The column is VARCHAR(16); UNCONFIRMED is 11 characters, so no widening needed.

ALTER TABLE inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_status;

ALTER TABLE inventory_reservations
    ADD CONSTRAINT ck_reservation_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'UNCONFIRMED'));