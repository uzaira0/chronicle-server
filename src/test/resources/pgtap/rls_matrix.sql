-- SKIPPED: encrypted_payloads: V95 enforces CHECK (false); no valid row can exercise its RLS policies without changing production constraints.
-- Context order: unset/false/true app.is_admin, each with owner/other principal and matching/foreign study.
-- Entries 13..16: deletion worker; quarantined member; quarantined admin; quarantined deletion worker.
-- Each four-bit entry is SELECT, INSERT, UPDATE, DELETE. Expectations are literal contracts, never derived from policy expressions.
CREATE TEMP TABLE rls_contract (
    table_name TEXT NOT NULL,
    scenario TEXT NOT NULL DEFAULT 'normal',
    expected TEXT[] NOT NULL CHECK (cardinality(expected) = 16),
    fixture JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (table_name, scenario)
);
INSERT INTO rls_contract (table_name, expected, fixture) VALUES
    ('activity_recognition_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('ambient_audio_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('android_device_sensor_availability', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('android_sensor_data', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('api_keys', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"device_id":"11111111-1111-1111-1111-111111111111"}'::jsonb),
    ('app_audio_activity', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('app_audio_content', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('app_network_usage', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('app_usage_survey', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('audit_logs', ARRAY['0100','0100','0100','0100','0100','0100','0100','0100','1100','1100','1100','1100','1100','0100','1100','1100'], '{}'::jsonb),
    ('battery_telemetry', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('candidates', ARRAY['1111','1111','0100','0100','1111','1111','0100','0100','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('chronicle_usage_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('chronicle_usage_stats', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('connectivity_state_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('data_collection_settings_revisions', ARRAY['1000','1000','0000','0000','1000','1000','0000','0000','1000','1000','1000','1000','1000','1000','1000','1000'], '{"settings_version":"1","setting":"{\"settingsVersion\":1}"}'::jsonb),
    ('data_deletion_form_access_revocations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"resource_kind":"ACCESS_CODE"}'::jsonb),
    ('data_deletion_operations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"mode":"COLLECTED_DATA_PURGE","status":"PREVIEW","registry_version":"1"}'::jsonb),
    ('data_deletion_steps', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"status":"PENDING"}'::jsonb),
    ('data_quality_alerts', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"alert_type":"MISSING_DATA"}'::jsonb),
    ('device_settings', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('devices', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('erased_device_key_tombstones', ARRAY['0000','0000','0000','0000','0000','0000','0000','0000','1000','1000','1000','1000','1110','0000','1000','1110'], '{"kind":"WITHDRAW_AND_ERASE","expires_at":"2080-01-01T00:00:00Z"}'::jsonb),
    ('export_capacity_reservations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('export_job_revocations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    -- Framework bootstrap leaves status nullable; seed a worker state for the UPDATE/DELETE probes.
    ('export_jobs', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"status":"PENDING","format":"CSV","request":{"participantIds":["tap-export-participant"]}}'::jsonb),
    ('filtered_apps', ARRAY['1111','1111','0100','0100','1111','1111','0100','0100','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('health_metrics', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('interaction_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('jobs', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"principal_type":"USER","status":"PENDING","definition":"{\"@type\":\"EmptyJobDefinition\",\"studyId\":\"11111111-1111-1111-1111-111111111111\",\"participantIds\":[\"tap-participant\"]}"}'::jsonb),
    ('mobile_withdrawal_requests', ARRAY['1100','1100','0000','0000','1100','1100','0000','0000','1100','1100','1100','1100','1100','1100','1100','1100'], '{}'::jsonb),
    ('notification_activity', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('notifications', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('organization_members', ARRAY['1111','1111','1000','0000','1111','1111','1000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('organization_quotas', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('organization_studies', ARRAY['1111','1111','0100','0100','1111','1111','0100','0100','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('participant_collection_acknowledgment', ARRAY['1100','1100','0100','0100','1100','1100','0100','0100','1100','1100','1100','1100','1100','1100','1100','1100'], '{}'::jsonb),
    ('participant_form_access_codes', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"form_kind":"APP_USAGE","issuer_type":"RESEARCHER"}'::jsonb),
    ('participant_form_sessions', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"form_kind":"APP_USAGE"}'::jsonb),
    ('participant_form_submission_receipts', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"form_kind":"APP_USAGE","status":"PROCESSING"}'::jsonb),
    ('participant_pseudonyms', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('participant_purge_cutoffs', ARRAY['1000','1000','0000','0000','1000','1000','0000','0000','1000','1000','1000','1000','1111','1000','1000','1111'], '{}'::jsonb),
    ('participant_stats', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('preprocessed_usage_events', ARRAY['0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000','0000'], '{}'::jsonb),
    ('questionnaire_submissions', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('questionnaires', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('refresh_tokens', ARRAY['1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('researcher_create_receipts', ARRAY['1111','0000','1111','0000','1111','0000','1111','0000','1111','0000','1111','0000','1001','1111','1111','1001'], '{}'::jsonb),
    ('researcher_notification_settings', ARRAY['1111','0000','0000','0000','1111','0000','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('researcher_phone_numbers', ARRAY['1111','0000','1111','0000','1111','0000','1111','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('retention_holds', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('sensor_data', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('sleep_events', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('study_anonymization_config', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('study_deletion_schedule', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('study_encryption_keys', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"backend":"FILE"}'::jsonb),
    ('study_event_stream', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('study_lifecycle_events', ARRAY['1100','1100','0000','0000','1100','1100','0000','0000','1100','1100','1100','1100','1100','1100','1100','1100'], '{}'::jsonb),
    ('study_limits', ARRAY['1111','1111','0100','0100','1111','1111','0100','0100','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    -- V68__make_deletion_proofs_observable_and_stable.sql:210,218 installs the INSERT guard; V79__isolate_upload_drain_from_deletion_quarantine.sql:32,111 denies quarantined writes.
    ('study_participants', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('study_realtime_stats', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{}'::jsonb),
    ('study_settings_audit', ARRAY['1100','1100','0100','0100','1100','1100','0100','0100','1100','1100','1100','1100','1100','1100','1100','1100'], '{}'::jsonb),
    ('time_use_diary_submissions', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('time_use_diary_summarized', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('upload_buffer', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb),
    ('upload_diagnostic_erasures', ARRAY['1000','1000','0000','0000','1000','1000','0000','0000','1000','1000','1000','1000','1001','1000','1000','1001'], '{}'::jsonb),
    ('upload_diagnostics', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"module_family":"USAGE_LIFECYCLE","issue_code":"UPLOAD_FAILURE","server_origin":"https://localhost"}'::jsonb),
    ('usage_event_annotations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{}'::jsonb);

INSERT INTO rls_contract (table_name, scenario, expected, fixture)
SELECT 'erased_device_key_tombstones', 'expired',
    ARRAY['0000','0000','0000','0000','0000','0000','0000','0000','1001','1001','1001','1001','1111','0000','1001','1111'],
    '{"kind":"WITHDRAW_AND_ERASE","expires_at":"2000-01-01T00:00:00Z"}'::jsonb;

CREATE TEMP TABLE rls_skipped (table_name TEXT PRIMARY KEY);
INSERT INTO rls_contract (table_name, expected, fixture) VALUES
    ('data_deletion_audit_outbox', ARRAY['1110','1110','0000','0000','1110','1110','0000','0000','1110','1110','1110','1110','1110','1110','1110','1110'], '{"event_type":"CANCEL_DATA_DELETION"}'::jsonb),
    ('webhook_registrations', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','1111','1111','1111'], '{"url":"https://localhost"}'::jsonb),
    ('webhook_deliveries', ARRAY['1111','1111','0000','0000','1111','1111','0000','0000','1111','1111','1111','1111','1111','0000','0000','1111'], '{"delivery_state":"PENDING","event_type":"DATA_SUBMITTED"}'::jsonb);
INSERT INTO rls_skipped VALUES ('encrypted_payloads');

CREATE TEMP TABLE rls_context (
    id INTEGER PRIMARY KEY, label TEXT, admin_setting TEXT, principal TEXT,
    authorized BOOLEAN, quarantined BOOLEAN DEFAULT false
);
INSERT INTO rls_context (id, label, admin_setting, principal, authorized, quarantined) VALUES
    (1, 'unset/owner/member', '', 'tap-owner', true, false),
    (2, 'unset/other/member', '', 'tap-other', true, false),
    (3, 'unset/owner/foreign', '', 'tap-owner', false, false),
    (4, 'unset/other/foreign', '', 'tap-other', false, false),
    (5, 'false/owner/member', 'false', 'tap-owner', true, false),
    (6, 'false/other/member', 'false', 'tap-other', true, false),
    (7, 'false/owner/foreign', 'false', 'tap-owner', false, false),
    (8, 'false/other/foreign', 'false', 'tap-other', false, false),
    (9, 'true/owner/member', 'true', 'tap-owner', true, false),
    (10, 'true/other/member', 'true', 'tap-other', true, false),
    (11, 'true/owner/foreign', 'true', 'tap-owner', false, false),
    (12, 'true/other/foreign', 'true', 'tap-other', false, false),
    (13, 'deletion-worker', 'true', 'chronicle-deletion-worker', false, false),
    (14, 'quarantined-member', 'false', 'tap-owner', true, true),
    (15, 'quarantined-admin', 'true', 'tap-owner', true, true),
    (16, 'quarantined-deletion-worker', 'true', 'chronicle-deletion-worker', false, true);

-- Fixture metadata comes from columns and foreign keys, not from RLS predicates.
CREATE FUNCTION pg_temp.rls_seed(target TEXT, supplied JSONB DEFAULT '{}', insert_row BOOLEAN DEFAULT true, visited TEXT[] DEFAULT '{}')
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    relation REGCLASS := format('public.%I', target)::regclass;
    attributes RECORD;
    parent RECORD;
    values_sql TEXT[] := '{}';
    columns_sql TEXT[] := '{}';
    overrides JSONB;
    expression TEXT;
    insertion TEXT;
BEGIN
    IF target = ANY(visited) THEN RAISE EXCEPTION 'Cyclic fixture foreign key: %', target; END IF;
    SELECT fixture INTO overrides FROM rls_contract WHERE table_name = target AND scenario = 'normal';
    overrides := COALESCE(overrides, '{}') || supplied;
    FOR parent IN
        SELECT DISTINCT c.confrelid::regclass::text AS name
        FROM pg_constraint c
        WHERE c.conrelid = relation AND c.contype = 'f'
          AND NOT EXISTS (
              SELECT 1 FROM unnest(c.conkey) key(attnum)
              JOIN pg_attribute a ON a.attrelid = relation AND a.attnum = key.attnum
              WHERE NOT a.attnotnull AND NOT overrides ? a.attname
          )
    LOOP
        PERFORM pg_temp.rls_seed(replace(parent.name, 'public.', ''), '{}', true, visited || target);
    END LOOP;

    FOR attributes IN
        SELECT a.attname, a.attnotnull, a.attgenerated, a.atttypid, t.typcategory,
               format_type(a.atttypid, a.atttypmod) AS type,
               d.adbin IS NOT NULL AS has_default,
               EXISTS (SELECT 1 FROM pg_constraint p WHERE p.conrelid = relation
                   AND p.contype = 'p' AND a.attnum = ANY(p.conkey)) AS primary_column
        FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid
        LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
        WHERE a.attrelid = relation AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum
    LOOP
        IF attributes.attgenerated <> '' THEN CONTINUE; END IF;
        IF overrides ? attributes.attname THEN
            IF overrides -> attributes.attname = 'null'::jsonb THEN
                expression := 'NULL';
            ELSE
                expression := format('%L::%s', overrides ->> attributes.attname, attributes.type);
            END IF;
        ELSIF attributes.attname IN ('study_id', 'organization_id') THEN
            expression := format('%L::%s', '11111111-1111-1111-1111-111111111111', attributes.type);
        ELSIF attributes.attname = 'participant_id' THEN
            expression := quote_literal('tap-participant');
        ELSIF attributes.attname = 'principal_id' OR (attributes.attname = 'user_id' AND attributes.typcategory = 'S') THEN
            expression := quote_literal('tap-owner');
        ELSIF attributes.has_default AND NOT attributes.primary_column THEN
            CONTINUE;
        -- The UPDATE/DELETE probes prefer message, so nullable message columns still need a value.
        ELSIF NOT attributes.attnotnull AND NOT attributes.primary_column AND attributes.attname <> 'message' THEN
            CONTINUE;
        ELSIF attributes.atttypid = 'uuid'::regtype THEN
            expression := quote_literal('11111111-1111-1111-1111-111111111111') || '::uuid';
        ELSIF attributes.atttypid IN ('json'::regtype, 'jsonb'::regtype) THEN
            expression := quote_literal('{}') || '::' || attributes.type;
        ELSIF attributes.atttypid = 'bytea'::regtype THEN
            expression := 'decode(repeat(''00'', 32), ''hex'')';
        ELSIF attributes.typcategory = 'A' THEN
            expression := quote_literal('{}') || '::' || attributes.type;
        ELSIF attributes.typcategory = 'N' THEN
            expression := '1';
        ELSIF attributes.typcategory = 'B' THEN
            expression := 'true';
        ELSIF attributes.typcategory = 'D' THEN
            expression := 'now()::' || attributes.type;
        ELSIF attributes.typcategory = 'S' THEN
            expression := quote_literal(CASE
                WHEN attributes.attname = 'event_id' THEN '11111111-1111-1111-1111-111111111111'
                WHEN attributes.attname = 'participation_status' THEN 'ENROLLED'
                WHEN attributes.attname = 'timezone' THEN 'UTC'
                ELSE 'tap-' || attributes.attname END);
        ELSE
            RAISE EXCEPTION 'Unsupported fixture type %.%: %', target, attributes.attname, attributes.type;
        END IF;
        columns_sql := array_append(columns_sql, quote_ident(attributes.attname));
        values_sql := array_append(values_sql, expression);
    END LOOP;
    insertion := format('INSERT INTO public.%I (%s) VALUES (%s)', target,
        array_to_string(columns_sql, ','), array_to_string(values_sql, ','));
    IF insert_row THEN EXECUTE insertion || ' ON CONFLICT DO NOTHING'; END IF;
    RETURN insertion;
END
$$;

CREATE FUNCTION pg_temp.rls_probe(target TEXT, scenario TEXT, fixture JSONB, context_id INTEGER, operation INTEGER, expected BOOLEAN)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    settings rls_context%ROWTYPE;
    insertion TEXT;
    column_name TEXT;
    row_count BIGINT := 0;
    actual BOOLEAN := false;
    switched BOOLEAN := false;
    failure TEXT;
    tap TEXT;
    probe_fixture JSONB := fixture;
BEGIN
    SELECT * INTO STRICT settings FROM rls_context WHERE id = context_id;
    BEGIN
        IF settings.quarantined AND target = 'data_deletion_operations' THEN
            probe_fixture := fixture || '{"mode":"STUDY_ERASURE","status":"QUARANTINED"}'::jsonb;
        END IF;
        insertion := pg_temp.rls_seed(target, probe_fixture, operation <> 2);
        IF target = 'candidates' THEN
            PERFORM pg_temp.rls_seed('study_participants');
        END IF;
        IF settings.quarantined AND target <> 'data_deletion_operations' THEN
            PERFORM pg_temp.rls_seed('data_deletion_operations',
                '{"operation_id":"22222222-2222-2222-2222-222222222222","idempotency_key":"22222222-2222-2222-2222-222222222222","mode":"STUDY_ERASURE","status":"QUARANTINED"}');
        END IF;
        SELECT a.attname INTO STRICT column_name FROM pg_attribute a
        WHERE a.attrelid = format('public.%I', target)::regclass AND a.attnum > 0 AND NOT a.attisdropped
          AND a.attgenerated = ''
        -- Export scope is immutable; exercise its permitted worker-state update instead.
        ORDER BY CASE
            WHEN target = 'export_jobs' AND a.attname = 'status' THEN 0
            WHEN a.attname = 'message' THEN 1
            ELSE 2
        END, a.attnum LIMIT 1;
        PERFORM set_config('app.is_admin', settings.admin_setting, true);
        PERFORM set_config('app.current_user_id', settings.principal, true);
        PERFORM set_config('app.authorized_studies', CASE WHEN settings.authorized
            THEN '11111111-1111-1111-1111-111111111111' ELSE '33333333-3333-3333-3333-333333333333' END, true);
        PERFORM set_config('app.authorized_orgs', CASE WHEN settings.authorized
            THEN '11111111-1111-1111-1111-111111111111' ELSE '33333333-3333-3333-3333-333333333333' END, true);
        EXECUTE 'SET LOCAL ROLE chronicle_app';
        switched := true;
        CASE operation
            WHEN 1 THEN EXECUTE format('SELECT count(*) FROM public.%I', target) INTO row_count;
            WHEN 2 THEN EXECUTE insertion;
            WHEN 3 THEN EXECUTE format('UPDATE public.%I SET %I = %I WHERE %I IS NOT NULL', target, column_name, column_name, column_name);
            WHEN 4 THEN EXECUTE format('DELETE FROM public.%I WHERE %I IS NOT NULL', target, column_name);
        END CASE;
        IF operation <> 1 THEN GET DIAGNOSTICS row_count = ROW_COUNT; END IF;
        actual := row_count = 1;
        -- Roll back both the probe and its fixture before the next matrix entry.
        RAISE EXCEPTION USING ERRCODE = 'ZQ001', MESSAGE = 'probe rollback';
    EXCEPTION
        WHEN SQLSTATE 'ZQ001' THEN NULL;
        WHEN insufficient_privilege THEN
            IF NOT switched THEN failure := SQLSTATE || ': fixture: ' || SQLERRM; END IF;
        WHEN SQLSTATE '55000' THEN
            IF NOT switched OR NOT settings.quarantined THEN failure := SQLSTATE || ': ' || SQLERRM; END IF;
        WHEN OTHERS THEN failure := SQLSTATE || ': ' || SQLERRM;
    END;
    tap := ok(failure IS NULL AND actual = expected,
        target || '/' || scenario || ' chronicle_app ' || settings.label || ' ' ||
        (ARRAY['SELECT','INSERT','UPDATE','DELETE'])[operation] || ' expected ' || CASE WHEN expected THEN 'allow' ELSE 'deny' END);
    IF failure IS NOT NULL THEN tap := tap || E'\n' || diag('fixture or statement error: ' || failure); END IF;
    RETURN tap;
END
$$;

SELECT plan(3 + (SELECT count(*)::integer * 16 * 4 FROM rls_contract));
SELECT ok(
    NOT has_table_privilege('chronicle_app', 'export_jobs', 'UPDATE')
    AND NOT has_column_privilege('chronicle_app', 'export_jobs', 'export_id', 'UPDATE')
    AND NOT has_column_privilege('chronicle_app', 'export_jobs', 'study_id', 'UPDATE')
    AND NOT has_column_privilege('chronicle_app', 'export_jobs', 'created_by', 'UPDATE'),
    'export identity and ownership remain immutable to chronicle_app');
SELECT results_eq(
    $sql$SELECT c.relname::text COLLATE "default" FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relrowsecurity ORDER BY 1$sql$,
    $sql$SELECT table_name FROM rls_contract UNION SELECT table_name FROM rls_skipped ORDER BY 1$sql$,
    'every public RLS table has an explicit matrix or a documented impossible fixture');
SELECT results_eq(
    $sql$SELECT DISTINCT tablename::text COLLATE "default" FROM pg_policies WHERE schemaname = 'public' ORDER BY 1$sql$,
    $sql$SELECT table_name FROM rls_contract UNION SELECT table_name FROM rls_skipped ORDER BY 1$sql$,
    'pg_policies enumeration agrees with the literal table inventory');
SELECT pg_temp.rls_probe(contract.table_name, contract.scenario, contract.fixture, context.id, operation.id,
    substring(contract.expected[context.id] FROM operation.id FOR 1) = '1')
FROM rls_contract contract CROSS JOIN rls_context context
CROSS JOIN generate_series(1, 4) operation(id)
ORDER BY contract.table_name, contract.scenario, context.id, operation.id;
SELECT * FROM finish();
