ALTER TABLE payment_events 
    ALTER COLUMN detail TYPE TEXT;

ALTER TABLE idempotency_keys 
    ALTER COLUMN response_body TYPE TEXT;