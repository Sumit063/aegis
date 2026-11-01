-- Identities enforced by the database, not only by code under a row lock (DECISIONS D25):
--   a provider message ID exists once per provider;
--   a forwardable business fact (outcome + fact reference) exists once per payment.
-- Rows are written in the same transaction as the payment row and deleted with it.

CREATE TABLE payment_messages (
    provider   text   NOT NULL,
    message_id text   NOT NULL,
    payment_id bigint NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    n          int    NOT NULL,
    PRIMARY KEY (provider, message_id)
);
CREATE INDEX payment_messages_by_payment ON payment_messages (payment_id);

CREATE TABLE payment_facts (
    payment_id bigint NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    outcome    text   NOT NULL,
    fact_ref   text   NOT NULL DEFAULT '',
    n          int    NOT NULL,
    PRIMARY KEY (payment_id, outcome, fact_ref)
);

-- Existing installations: backfill from what is already stored.
INSERT INTO payment_messages (provider, message_id, payment_id, n)
SELECT p.provider, e ->> 'messageId', p.id, (e ->> 'n')::int
FROM payments p, jsonb_array_elements(p.messages) e
ON CONFLICT DO NOTHING;

INSERT INTO payment_facts (payment_id, outcome, fact_ref, n)
SELECT p.id, e ->> 'outcome', coalesce(e ->> 'factRef', ''), (e ->> 'n')::int
FROM payments p, jsonb_array_elements(p.messages) e
WHERE e ->> 'verdict' = 'PASSED'
  AND e ->> 'type' IN ('CALLBACK', 'WEBHOOK', 'ASYNC_RESPONSE')
  AND e ->> 'outcome' <> 'OTHER'
ON CONFLICT DO NOTHING;
