-- Drop the relay's bookkeeping from the outbox.
--
-- Delivery is now Change Data Capture: Debezium tails the PostgreSQL
-- write-ahead log and publishes each inserted row to Kafka, so nothing reads
-- this table back and nothing updates it after insert.
--
-- published_at / attempts / last_error existed only so the deleted polling relay
-- could claim a batch, mark it done and retry failures. Left in place they would
-- be permanently NULL / zero, which is worse than useless - a query for
-- "what is still unpublished?" would match the entire table forever.
--
-- idx_outbox_unpublished was a partial index over (created_at, id) WHERE
-- published_at IS NULL, serving only the relay's hot query. With published_at
-- gone the predicate no longer exists, and the index would cover every row.
--
-- No data is lost: every column dropped here described relay progress, not an
-- event. The event itself (event_id, topic, payload, envelope metadata) is
-- untouched and still CDC-visible.

DROP INDEX IF EXISTS idx_outbox_unpublished;

ALTER TABLE outbox_events DROP COLUMN IF EXISTS published_at;
ALTER TABLE outbox_events DROP COLUMN IF EXISTS attempts;
ALTER TABLE outbox_events DROP COLUMN IF EXISTS last_error;