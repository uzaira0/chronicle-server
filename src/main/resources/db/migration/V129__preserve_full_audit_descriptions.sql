-- chronicle:destructive-approved Pure widening from varchar(256) to binary-coercible text; no table rewrite or data changes, and every existing row remains valid.
-- Bulk deletion and valid API-key names can exceed the old description limit.
ALTER TABLE audit ALTER COLUMN description TYPE text;
ALTER TABLE audit_buffer ALTER COLUMN description TYPE text;
