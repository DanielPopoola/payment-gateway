CREATE TABLE IF NOT EXISTS payments (
    id               UUID PRIMARY KEY,
    order_id         VARCHAR NOT NULL,
    customer_id      BIGINT NOT NULL,
    amount_cents     BIGINT NOT NULL,
    currency         VARCHAR(3) NOT NULL DEFAULT 'USD',
    status           VARCHAR(20) NOT NULL,
    bank_auth_id     VARCHAR,
    bank_capture_id  VARCHAR,
    bank_void_id     VARCHAR,
    bank_refund_id   VARCHAR,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    authorized_at    TIMESTAMPTZ,
    captured_at      TIMESTAMPTZ,
    voided_at        TIMESTAMPTZ,
    refunded_at      TIMESTAMPTZ,
    failed_at        TIMESTAMPTZ,
    expired_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_payments_order_id ON payments(order_id);
CREATE INDEX IF NOT EXISTS idx_payments_customer_id ON payments(customer_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  UUID NOT NULL,
    payment_id       UUID NULL REFERENCES payments(id),
    operation        TEXT NOT NULL,
    request_hash     TEXT NOT NULL,
    locked_at        TIMESTAMPTZ DEFAULT now(),
    last_run_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_code    INT,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_idempotency_key
    ON idempotency_keys(idempotency_key);

CREATE TABLE IF NOT EXISTS payment_events (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       UUID NOT NULL REFERENCES payments(id),
    idempotency_key  UUID NOT NULL,
    event_type       TEXT NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
