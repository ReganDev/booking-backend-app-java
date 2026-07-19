-- Business photos shown on the public booking page, stored as an ordered
-- array of image URLs.
ALTER TABLE businesses ADD COLUMN photo_urls TEXT[] NOT NULL DEFAULT '{}';
