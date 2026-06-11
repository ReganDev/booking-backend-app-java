-- 'United Kingdom/...' is not a valid IANA zone ID and breaks ZoneId.of()
ALTER TABLE businesses ALTER COLUMN timezone SET DEFAULT 'Europe/London';

UPDATE businesses
SET timezone = 'Europe/London'
WHERE timezone LIKE 'United Kingdom/%';
