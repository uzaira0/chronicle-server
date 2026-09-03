-- V81: preserve every DeviceActivity capture as a raw cumulative snapshot. The application
-- supplies a capture-specific sample_id; retry deduplication is handled by the iOS mover.
ALTER TABLE sensor_data
    ADD COLUMN IF NOT EXISTS ios_screen_time_notification_count INTEGER,
    ADD COLUMN IF NOT EXISTS ios_screen_time_pickup_count INTEGER;

CREATE INDEX IF NOT EXISTS sensor_data_direct_screen_time_snapshots_idx
    ON sensor_data (study_id, participant_id, datetimestart, exact_recordeddate)
    WHERE ios_screen_time_source = 'deviceActivityExport';

-- Apple reports a cumulative total for each absolute DeviceActivity segment.  This view
-- converts successive snapshots into non-overlapping intervals without ever truncating or
-- grouping timestamps in local civil time.  Consequently midnight, spring-forward gaps,
-- and the two distinct fall-back 01:00 hours require no special cases.
CREATE OR REPLACE VIEW screen_time_usage_deltas
WITH (security_invoker = true)
AS
WITH raw_snapshots AS (
    SELECT
        study_id,
        participant_id,
        device_name AS device_key,
        sample_id,
        COALESCE(exact_recordeddate, recordeddate) AS captured_at,
        datetimestart AS bucket_start_utc,
        datetimeend AS bucket_end_utc,
        timezone AS source_timezone,
        ios_screen_time_row_kind AS row_kind,
        CASE ios_screen_time_row_kind
            WHEN 'application' THEN COALESCE(
                NULLIF(ios_screen_time_bundle_id, ''),
                NULLIF(bundle_identifier, ''),
                NULLIF(ios_screen_time_app_label, ''),
                NULLIF(app_category, '')
            )
            WHEN 'webDomain' THEN COALESCE(
                NULLIF(ios_screen_time_web_domain, ''),
                NULLIF(app_category, '')
            )
        END AS entity_key,
        ios_screen_time_app_label AS app_label,
        COALESCE(NULLIF(ios_screen_time_bundle_id, ''), NULLIF(bundle_identifier, '')) AS bundle_identifier,
        ios_screen_time_web_domain AS web_domain,
        app_category AS source_category,
        CASE ios_screen_time_row_kind
            WHEN 'application' THEN app_usage_time
            WHEN 'webDomain' THEN app_category_web_duration
        END AS cumulative_usage_seconds,
        ios_screen_time_notification_count AS cumulative_notification_count,
        COALESCE(ios_screen_time_pickup_count, total_screen_wakes) AS cumulative_pickup_count,
        LEAST(
            datetimeend,
            GREATEST(datetimestart, COALESCE(exact_recordeddate, recordeddate))
        ) AS effective_capture_at
    FROM sensor_data
    WHERE ios_screen_time_source = 'deviceActivityExport'
      AND ios_screen_time_row_kind IN ('application', 'webDomain')
      AND datetimestart IS NOT NULL
      AND datetimeend IS NOT NULL
      AND datetimeend > datetimestart
      AND EXTRACT(EPOCH FROM datetimeend - datetimestart) BETWEEN 3540 AND 3660
      AND COALESCE(exact_recordeddate, recordeddate) IS NOT NULL
), ranked_snapshots AS (
    SELECT
        raw_snapshots.*,
        row_number() OVER (
            PARTITION BY
                study_id,
                participant_id,
                device_key,
                bucket_start_utc,
                bucket_end_utc,
                row_kind,
                entity_key,
                effective_capture_at
            ORDER BY captured_at DESC, sample_id DESC
        ) AS correction_rank
    FROM raw_snapshots
    WHERE entity_key IS NOT NULL
      AND cumulative_usage_seconds IS NOT NULL
      AND cumulative_usage_seconds >= 0
), ordered_snapshots AS (
    SELECT
        ranked_snapshots.*,
        lag(effective_capture_at) OVER snapshot_order AS previous_effective_capture_at,
        lag(cumulative_usage_seconds) OVER snapshot_order AS previous_usage_seconds,
        lag(cumulative_notification_count) OVER snapshot_order AS previous_notification_count,
        lag(cumulative_pickup_count) OVER snapshot_order AS previous_pickup_count
    FROM ranked_snapshots
    WHERE correction_rank = 1
    WINDOW snapshot_order AS (
        PARTITION BY
            study_id,
            participant_id,
            device_key,
            bucket_start_utc,
            bucket_end_utc,
            row_kind,
            entity_key
        ORDER BY effective_capture_at, sample_id
    )
)
SELECT
    study_id,
    participant_id,
    device_key,
    row_kind,
    entity_key,
    app_label,
    bundle_identifier,
    web_domain,
    source_category,
    source_timezone,
    sample_id,
    captured_at,
    bucket_start_utc,
    bucket_end_utc,
    COALESCE(previous_effective_capture_at, bucket_start_utc) AS interval_start_utc,
    effective_capture_at AS interval_end_utc,
    EXTRACT(
        EPOCH FROM effective_capture_at - COALESCE(previous_effective_capture_at, bucket_start_utc)
    )::double precision AS interval_seconds,
    cumulative_usage_seconds,
    CASE
        WHEN previous_usage_seconds IS NULL THEN cumulative_usage_seconds
        WHEN cumulative_usage_seconds >= previous_usage_seconds
            THEN cumulative_usage_seconds - previous_usage_seconds
    END AS usage_delta_seconds,
    cumulative_notification_count,
    CASE
        WHEN cumulative_notification_count IS NULL THEN NULL
        WHEN previous_notification_count IS NULL THEN cumulative_notification_count
        WHEN cumulative_notification_count >= previous_notification_count
            THEN cumulative_notification_count - previous_notification_count
    END AS notification_delta_count,
    cumulative_pickup_count,
    CASE
        WHEN cumulative_pickup_count IS NULL THEN NULL
        WHEN previous_pickup_count IS NULL THEN cumulative_pickup_count
        WHEN cumulative_pickup_count >= previous_pickup_count
            THEN cumulative_pickup_count - previous_pickup_count
    END AS pickup_delta_count,
    CASE
        WHEN previous_usage_seconds IS NOT NULL AND cumulative_usage_seconds < previous_usage_seconds
            THEN 'counter_decreased'
        WHEN previous_notification_count IS NOT NULL
            AND cumulative_notification_count < previous_notification_count
            THEN 'counter_decreased'
        WHEN previous_pickup_count IS NOT NULL AND cumulative_pickup_count < previous_pickup_count
            THEN 'counter_decreased'
        ELSE 'ok'
    END AS delta_status
FROM ordered_snapshots
WHERE effective_capture_at > COALESCE(previous_effective_capture_at, bucket_start_utc);

COMMENT ON VIEW screen_time_usage_deltas IS
    'UTC-safe intervals and deltas derived from raw cumulative hourly iOS DeviceActivity snapshots; malformed non-hourly rows remain raw in sensor_data but are excluded here.';
COMMENT ON COLUMN screen_time_usage_deltas.captured_at IS
    'Actual collection instant. It is not a bucket boundary and must not be rounded for ordering.';
COMMENT ON COLUMN screen_time_usage_deltas.bucket_start_utc IS
    'Apple DeviceActivity segment start as an absolute timestamptz; distinct DST fall-back hours remain distinct.';
COMMENT ON COLUMN screen_time_usage_deltas.interval_start_utc IS
    'Inclusive start of the derived observation interval.';
COMMENT ON COLUMN screen_time_usage_deltas.interval_end_utc IS
    'Exclusive end of the derived observation interval.';
COMMENT ON COLUMN screen_time_usage_deltas.delta_status IS
    'ok, or counter_decreased when an Apple cumulative counter moved backward; affected negative deltas are NULL.';

GRANT SELECT ON screen_time_usage_deltas TO chronicle, chronicle_app;
