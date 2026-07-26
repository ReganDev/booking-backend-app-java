-- Custom recurrence spacing: every N weeks or every N months.
ALTER TABLE booking_series
    ADD COLUMN interval_weeks INT NOT NULL DEFAULT 1,
    ADD COLUMN interval_months INT NOT NULL DEFAULT 1;

ALTER TABLE booking_series
    ADD CONSTRAINT valid_interval_weeks CHECK (interval_weeks BETWEEN 1 AND 52),
    ADD CONSTRAINT valid_interval_months CHECK (interval_months BETWEEN 1 AND 12);

-- Fortnightly series created before this migration used FORTNIGHTLY frequency.
UPDATE booking_series SET interval_weeks = 2 WHERE frequency = 'FORTNIGHTLY';
