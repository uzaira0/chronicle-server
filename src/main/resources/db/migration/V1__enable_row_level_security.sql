-- =============================================================================
-- Row-Level Security (RLS) Migration for Chronicle Platform
-- =============================================================================
-- This migration enables PostgreSQL Row-Level Security on all study-scoped tables
-- to enforce data isolation at the database level. This provides defense-in-depth
-- security - even if application authorization is bypassed, the database enforces
-- study-level isolation.
--
-- RLS Context Variables (set per connection):
--   app.current_user_id     - The authenticated user's principal ID
--   app.authorized_studies  - Comma-separated list of study UUIDs the user can access
--   app.is_admin            - Boolean flag for admin bypass (true/false)
--
-- Usage:
--   SET app.current_user_id = 'user-principal-id';
--   SET app.authorized_studies = 'uuid1,uuid2,uuid3';
--   SET app.is_admin = 'false';
-- =============================================================================

-- =============================================================================
-- HELPER FUNCTION: Check if current session has access to a study
-- =============================================================================
CREATE OR REPLACE FUNCTION chronicle_has_study_access(check_study_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    is_admin_user BOOLEAN;
    authorized_studies_setting TEXT;
    authorized_studies UUID[];
BEGIN
    -- Check if user is admin (bypass RLS)
    is_admin_user := COALESCE(
        NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN,
        false
    );

    IF is_admin_user THEN
        RETURN true;
    END IF;

    -- Get authorized studies from session variable
    authorized_studies_setting := current_setting('app.authorized_studies', true);

    -- If no authorized studies set, deny access
    IF authorized_studies_setting IS NULL OR authorized_studies_setting = '' THEN
        RETURN false;
    END IF;

    -- Parse the comma-separated list of UUIDs
    BEGIN
        authorized_studies := string_to_array(authorized_studies_setting, ',')::UUID[];
    EXCEPTION WHEN OTHERS THEN
        -- If parsing fails, deny access
        RETURN false;
    END;

    -- Check if the study_id is in the authorized list
    RETURN check_study_id = ANY(authorized_studies);
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- Grant execute permission to public (will be restricted by RLS policies)
GRANT EXECUTE ON FUNCTION chronicle_has_study_access(UUID) TO PUBLIC;

-- Overload for tables where study_id is VARCHAR instead of UUID
CREATE OR REPLACE FUNCTION chronicle_has_study_access(check_study_id TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF check_study_id IS NULL OR check_study_id = '' THEN
        RETURN false;
    END IF;
    RETURN chronicle_has_study_access(check_study_id::UUID);
EXCEPTION WHEN OTHERS THEN
    RETURN false;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION chronicle_has_study_access(TEXT) TO PUBLIC;

-- =============================================================================
-- Enable RLS on study_participants table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'study_participants') THEN
        ALTER TABLE study_participants ENABLE ROW LEVEL SECURITY;
        ALTER TABLE study_participants FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_study_participants ON study_participants;
        CREATE POLICY study_isolation_study_participants ON study_participants
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on notifications table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'notifications') THEN
        ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
        ALTER TABLE notifications FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_notifications ON notifications;
        CREATE POLICY study_isolation_notifications ON notifications
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on time_use_diary_submissions table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'time_use_diary_submissions') THEN
        ALTER TABLE time_use_diary_submissions ENABLE ROW LEVEL SECURITY;
        ALTER TABLE time_use_diary_submissions FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_tud_submissions ON time_use_diary_submissions;
        CREATE POLICY study_isolation_tud_submissions ON time_use_diary_submissions
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on questionnaires table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'questionnaires') THEN
        ALTER TABLE questionnaires ENABLE ROW LEVEL SECURITY;
        ALTER TABLE questionnaires FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_questionnaires ON questionnaires;
        CREATE POLICY study_isolation_questionnaires ON questionnaires
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on questionnaire_submissions table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'questionnaire_submissions') THEN
        ALTER TABLE questionnaire_submissions ENABLE ROW LEVEL SECURITY;
        ALTER TABLE questionnaire_submissions FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_questionnaire_submissions ON questionnaire_submissions;
        CREATE POLICY study_isolation_questionnaire_submissions ON questionnaire_submissions
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on participant_stats table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'participant_stats') THEN
        ALTER TABLE participant_stats ENABLE ROW LEVEL SECURITY;
        ALTER TABLE participant_stats FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_participant_stats ON participant_stats;
        CREATE POLICY study_isolation_participant_stats ON participant_stats
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on time_use_diary_summarized table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'time_use_diary_summarized') THEN
        ALTER TABLE time_use_diary_summarized ENABLE ROW LEVEL SECURITY;
        ALTER TABLE time_use_diary_summarized FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_tud_summarized ON time_use_diary_summarized;
        CREATE POLICY study_isolation_tud_summarized ON time_use_diary_summarized
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on filtered_apps table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'filtered_apps') THEN
        ALTER TABLE filtered_apps ENABLE ROW LEVEL SECURITY;
        ALTER TABLE filtered_apps FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_filtered_apps ON filtered_apps;
        CREATE POLICY study_isolation_filtered_apps ON filtered_apps
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on devices table (note: table name is uppercase DEVICES in code)
-- =============================================================================
DO $$
BEGIN
    -- Try lowercase first (standard), then uppercase if it doesn't exist
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'devices') THEN
        ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
        ALTER TABLE devices FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_devices ON devices;
        CREATE POLICY study_isolation_devices ON devices
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    ELSIF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'DEVICES') THEN
        ALTER TABLE "DEVICES" ENABLE ROW LEVEL SECURITY;
        ALTER TABLE "DEVICES" FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_devices ON "DEVICES";
        CREATE POLICY study_isolation_devices ON "DEVICES"
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on app_usage_survey table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'app_usage_survey') THEN
        ALTER TABLE app_usage_survey ENABLE ROW LEVEL SECURITY;
        ALTER TABLE app_usage_survey FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_app_usage_survey ON app_usage_survey;
        CREATE POLICY study_isolation_app_usage_survey ON app_usage_survey
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on upload_buffer table
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upload_buffer') THEN
        ALTER TABLE upload_buffer ENABLE ROW LEVEL SECURITY;
        ALTER TABLE upload_buffer FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_upload_buffer ON upload_buffer;
        CREATE POLICY study_isolation_upload_buffer ON upload_buffer
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on chronicle_usage_events table (data storage)
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'chronicle_usage_events') THEN
        ALTER TABLE chronicle_usage_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE chronicle_usage_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_usage_events ON chronicle_usage_events;
        CREATE POLICY study_isolation_usage_events ON chronicle_usage_events
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on chronicle_usage_stats table (data storage)
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'chronicle_usage_stats') THEN
        ALTER TABLE chronicle_usage_stats ENABLE ROW LEVEL SECURITY;
        ALTER TABLE chronicle_usage_stats FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_usage_stats ON chronicle_usage_stats;
        CREATE POLICY study_isolation_usage_stats ON chronicle_usage_stats
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on sensor_data table (iOS sensor data)
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'sensor_data')
       AND EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_name = 'sensor_data'
              AND column_name = 'study_id'
       ) THEN
        ALTER TABLE sensor_data ENABLE ROW LEVEL SECURITY;
        ALTER TABLE sensor_data FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_sensor_data ON sensor_data;
        CREATE POLICY study_isolation_sensor_data ON sensor_data
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on study_limits table (study-scoped limits)
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'study_limits') THEN
        ALTER TABLE study_limits ENABLE ROW LEVEL SECURITY;
        ALTER TABLE study_limits FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_study_limits ON study_limits;
        CREATE POLICY study_isolation_study_limits ON study_limits
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Enable RLS on organization_studies table (study membership)
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organization_studies') THEN
        ALTER TABLE organization_studies ENABLE ROW LEVEL SECURITY;
        ALTER TABLE organization_studies FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_org_studies ON organization_studies;
        CREATE POLICY study_isolation_org_studies ON organization_studies
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- COMMENT: Tables NOT protected by RLS (intentionally)
-- =============================================================================
-- The following tables are NOT protected by study-level RLS:
--   - studies: The study master table itself (protected by application authorization)
--   - organizations: Organization master data (protected by application authorization)
--   - users: User account data (not study-scoped)
--   - principals: Security principal data (not study-scoped)
--   - permissions: ACL permissions (not study-scoped)
--   - candidates: Candidate PII (cross-study, protected by application auth)
--   - default_filtered_apps: System-wide app filtering (not study-scoped)
--   - upgrades: Migration tracking (system table)
--   - audit: Audit logs (managed separately)
--   - jobs: Background jobs (managed by system)
-- =============================================================================

-- =============================================================================
-- Create index to improve RLS policy performance
-- =============================================================================
-- These indexes help PostgreSQL efficiently filter rows by study_id

DO $$
DECLARE
    tbl RECORD;
BEGIN
    FOR tbl IN
        SELECT unnest(ARRAY[
            'study_participants',
            'notifications',
            'time_use_diary_submissions',
            'questionnaires',
            'questionnaire_submissions',
            'participant_stats',
            'time_use_diary_summarized'
        ]) AS table_name,
        unnest(ARRAY[
            'idx_study_participants_study_id',
            'idx_notifications_study_id',
            'idx_tud_submissions_study_id',
            'idx_questionnaires_study_id',
            'idx_questionnaire_submissions_study_id',
            'idx_participant_stats_study_id',
            'idx_tud_summarized_study_id'
        ]) AS index_name
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = tbl.table_name) THEN
            EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I(study_id)', tbl.index_name, tbl.table_name);
        END IF;
    END LOOP;
END $$;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V1__enable_row_level_security', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
