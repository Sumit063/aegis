-- Refunds are facts on a captured payment, not a payment state (H6): a payment's status is its
-- lifecycle, PENDING, SUCCESS, FAILED or REVERSED. Only a captured payment could be refunded, so a
-- stored REFUNDED status was SUCCESS before it. REVERSED was missing from the original constraint.
-- REFUNDED stays allowed so that a rolled-back older version can still write; it is read as SUCCESS.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
UPDATE payments SET status = 'SUCCESS' WHERE status = 'REFUNDED';
ALTER TABLE payments
    ADD CONSTRAINT payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REVERSED', 'REFUNDED'));

-- Retention counts from the last activity on a record, not its creation.
DROP INDEX payments_by_created;
CREATE INDEX payments_by_activity ON payments (updated_at);
CREATE INDEX validations_by_activity ON validations (updated_at);
