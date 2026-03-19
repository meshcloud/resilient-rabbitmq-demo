-- Order-service schema
-- Flyway migration V1

CREATE TABLE IF NOT EXISTS orders
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    VARCHAR(36)  NOT NULL UNIQUE,
    customer_id VARCHAR(255) NOT NULL,
    product     VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbound_events
(
    id               BIGSERIAL PRIMARY KEY,
    type             VARCHAR(100) NOT NULL,
    payload          TEXT         NOT NULL,
    sent             BOOLEAN      NOT NULL DEFAULT false,
    failed_retries   INT          NOT NULL DEFAULT 0,
    last_modified_on TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Critical index: supports the "FOR UPDATE SKIP LOCKED" batch query efficiently.
-- Range scan on (sent=false, last_modified_on ASC) + early exit on failed_retries.
CREATE INDEX IF NOT EXISTS idx_outbound_events_pending
    ON outbound_events (sent, last_modified_on, failed_retries)
    WHERE sent = false;
