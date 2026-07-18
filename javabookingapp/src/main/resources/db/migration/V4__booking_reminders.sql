-- Customer reminder preferences chosen at booking time.
ALTER TABLE bookings ADD COLUMN email_reminder BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bookings ADD COLUMN sms_reminder BOOLEAN NOT NULL DEFAULT FALSE;
