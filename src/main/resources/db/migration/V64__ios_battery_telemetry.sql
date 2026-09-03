-- iOS battery telemetry support (per-module consent parity build, 2026-07-16).
--
-- iOS exposes only charge level, a coarse charging state, and Low Power Mode —
-- no temperature, voltage, health, or plug type. Those Android-hardware columns
-- become nullable so iOS rows store NULL instead of fabricated values, and a
-- nullable low_power_mode column carries the one iOS-only signal. Android rows
-- are unaffected: the Android client always supplies every hardware field.

ALTER TABLE battery_telemetry ALTER COLUMN plug_type DROP NOT NULL;
ALTER TABLE battery_telemetry ALTER COLUMN health DROP NOT NULL;
ALTER TABLE battery_telemetry ALTER COLUMN temperature_deci_c DROP NOT NULL;
ALTER TABLE battery_telemetry ALTER COLUMN voltage_millivolts DROP NOT NULL;
ALTER TABLE battery_telemetry ADD COLUMN IF NOT EXISTS low_power_mode boolean;
