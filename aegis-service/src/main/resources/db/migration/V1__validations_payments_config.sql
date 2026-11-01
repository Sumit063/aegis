-- One row per validation and one row per payment; everything exchanged in that cycle lives in the
-- row's JSON field (LLD §7, DECISIONS D3). Bodies inside the JSON are AES-256-GCM ciphertext.

CREATE TABLE validations (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider     text        NOT NULL,
    merchant_ref text        NOT NULL,
    provider_ref text,
    amount       bigint      CHECK (amount >= 0),
    currency     char(3),
    status       text        NOT NULL CHECK (status IN ('CREATED', 'FAILED', 'PAID')),
    data         jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT validations_provider_ref UNIQUE (provider, provider_ref)
);
CREATE INDEX validations_by_merchant_ref ON validations (provider, merchant_ref);

CREATE TABLE payments (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    validation_id  bigint REFERENCES validations (id),
    provider       text        NOT NULL,
    payment_ref    text,
    status         text CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    messages       jsonb       NOT NULL DEFAULT '[]',
    forward_due_at timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT payments_payment_ref UNIQUE (provider, payment_ref)
);
CREATE INDEX payments_by_validation ON payments (validation_id);
CREATE INDEX payments_forward_due ON payments (forward_due_at) WHERE forward_due_at IS NOT NULL;
CREATE INDEX payments_by_created ON payments (created_at);

CREATE TABLE config (
    key        text PRIMARY KEY,
    value      jsonb       NOT NULL,
    version    int         NOT NULL,
    updated_by text        NOT NULL,
    updated_at timestamptz NOT NULL
);
