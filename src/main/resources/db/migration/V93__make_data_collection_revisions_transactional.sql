-- Consent acknowledgments may arrive after newer settings have been issued. Their cited
-- DataCollection revision must therefore be recoverable from an immutable ledger written in
-- the same transaction as studies.settings, not from the best-effort human audit trail.

-- Runtime and BYPASSRLS roles must never own this table or its SECURITY DEFINER writer.
-- Production migrations run as the distinct offline schema owner (normally `chronicle`).
DO $$
DECLARE
    migration_role RECORD;
BEGIN
    SELECT rolsuper, rolbypassrls
    INTO migration_role
    FROM pg_catalog.pg_roles
    WHERE rolname = current_user;

    IF current_user IN ('chronicle_app', 'chronicle_admin')
       OR (NOT migration_role.rolsuper AND migration_role.rolbypassrls) THEN
        RAISE EXCEPTION
            'DataCollection revision migration requires a distinct trusted schema owner without BYPASSRLS';
    END IF;
END $$;

CREATE TABLE data_collection_settings_revisions (
    study_id UUID NOT NULL REFERENCES studies(study_id) ON DELETE CASCADE,
    settings_version INTEGER NOT NULL CHECK (settings_version > 0),
    setting JSONB NOT NULL CHECK (
        jsonb_typeof(setting) = 'object'
        AND setting ? 'settingsVersion'
        AND (setting ->> 'settingsVersion')::INTEGER = settings_version
    ),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, settings_version)
);

-- A currently published (study, version) that names more than one legacy payload is
-- irreconcilable. Abort before the current payload can be promoted to immutable authority;
-- the operator must publish a new settingsVersion after reconciling the study. Ambiguous
-- historical versions are omitted by the backfill below, so delayed acknowledgments that cite
-- them remain fail-closed without blocking a study that has already advanced to a new revision.
DO $$
BEGIN
    IF EXISTS (
        WITH candidates AS (
            SELECT audit.study_id, revision.value AS setting,
                   (revision.value ->> 'settingsVersion')::INTEGER AS settings_version
            FROM study_settings_audit AS audit
            CROSS JOIN LATERAL (VALUES (audit.after_value), (audit.before_value)) AS revision(value)
            WHERE audit.setting_key = 'DataCollection'
              AND revision.value IS NOT NULL
              AND jsonb_typeof(revision.value) = 'object'
              AND revision.value ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
        ), ambiguous AS (
            SELECT study_id, settings_version
            FROM candidates
            GROUP BY study_id, settings_version
            HAVING count(DISTINCT setting) > 1
        )
        SELECT 1
        FROM ambiguous
        JOIN studies AS study
          ON study.study_id = ambiguous.study_id
         AND jsonb_typeof(study.settings -> 'DataCollection') = 'object'
         AND study.settings -> 'DataCollection' ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
         AND (study.settings -> 'DataCollection' ->> 'settingsVersion')::INTEGER =
             ambiguous.settings_version
    ) THEN
        RAISE EXCEPTION
            'Ambiguous legacy DataCollection revision evidence; reconcile the study and publish a new settingsVersion';
    END IF;
END $$;

-- Recover unambiguous historical revisions from the prior audit source. Ambiguous revisions
-- are deliberately omitted and remain fail-closed rather than selecting arbitrary evidence.
WITH candidates AS (
    SELECT audit.study_id, revision.value AS setting,
           (revision.value ->> 'settingsVersion')::INTEGER AS settings_version
    FROM study_settings_audit AS audit
    CROSS JOIN LATERAL (VALUES (audit.after_value), (audit.before_value)) AS revision(value)
    WHERE audit.setting_key = 'DataCollection'
      AND revision.value IS NOT NULL
      AND jsonb_typeof(revision.value) = 'object'
      AND revision.value ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
), unambiguous AS (
    SELECT study_id, settings_version, min(setting::TEXT)::JSONB AS setting
    FROM candidates
    GROUP BY study_id, settings_version
    HAVING count(DISTINCT setting) = 1
)
INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting)
SELECT study_id, settings_version, setting
FROM unambiguous
ON CONFLICT (study_id, settings_version) DO NOTHING;

-- Always register the currently published revision, including studies whose earlier
-- best-effort audit write was lost.
INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting)
SELECT study_id, (settings -> 'DataCollection' ->> 'settingsVersion')::INTEGER,
       settings -> 'DataCollection'
FROM studies
WHERE jsonb_typeof(settings -> 'DataCollection') = 'object'
  AND settings -> 'DataCollection' ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
ON CONFLICT (study_id, settings_version) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM studies AS study
        JOIN data_collection_settings_revisions AS revision
          ON revision.study_id = study.study_id
         AND revision.settings_version =
             (study.settings -> 'DataCollection' ->> 'settingsVersion')::INTEGER
        WHERE jsonb_typeof(study.settings -> 'DataCollection') = 'object'
          AND study.settings -> 'DataCollection' ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
          AND revision.setting <> study.settings -> 'DataCollection'
    ) THEN
        RAISE EXCEPTION 'Current DataCollection revision conflicts with immutable historical evidence';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION record_data_collection_settings_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    candidate JSONB;
    candidate_version INTEGER;
BEGIN
    IF TG_RELID IS DISTINCT FROM 'public.studies'::pg_catalog.regclass
       OR TG_OP NOT IN ('INSERT', 'UPDATE') THEN
        RAISE EXCEPTION
            'DataCollection revision writer may execute only from the public.studies settings trigger';
    END IF;

    candidate := NEW.settings -> 'DataCollection';
    IF candidate IS NULL OR jsonb_typeof(candidate) <> 'object' THEN
        RETURN NEW;
    END IF;
    -- Legacy ChronicleDataCollectionSettings has no server-issued revision. Keep the legacy
    -- study write compatible, but never manufacture immutable evidence for it. Once a revision
    -- field is present, malformed values fail closed rather than disappearing from authority.
    IF NOT (candidate ? 'settingsVersion') THEN
        RETURN NEW;
    END IF;
    IF COALESCE(candidate ->> 'settingsVersion', '') !~ '^[1-9][0-9]*$' THEN
        RAISE EXCEPTION 'DataCollection settingsVersion must be a positive server-issued revision';
    END IF;
    candidate_version := (candidate ->> 'settingsVersion')::INTEGER;

    INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting)
    VALUES (NEW.study_id, candidate_version, candidate)
    ON CONFLICT (study_id, settings_version) DO NOTHING;

    IF NOT FOUND AND NOT EXISTS (
        SELECT 1
        FROM data_collection_settings_revisions
        WHERE study_id = NEW.study_id
          AND settings_version = candidate_version
          AND setting = candidate
    ) THEN
        RAISE EXCEPTION 'DataCollection revision % for study % already has different immutable evidence',
            candidate_version, NEW.study_id
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER record_data_collection_settings_revision
AFTER INSERT OR UPDATE OF settings ON studies
FOR EACH ROW EXECUTE FUNCTION record_data_collection_settings_revision();

ALTER TABLE data_collection_settings_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_collection_settings_revisions FORCE ROW LEVEL SECURITY;

CREATE POLICY study_isolation_data_collection_settings_revisions
    ON data_collection_settings_revisions
    FOR SELECT
    USING (chronicle_has_study_access(study_id));

-- The definer may append only while executing the studies.settings trigger. FORCE RLS makes
-- this reject a direct INSERT even by a non-superuser table/function owner.
CREATE POLICY trigger_only_data_collection_settings_revision_insert
    ON data_collection_settings_revisions
    FOR INSERT
    WITH CHECK (pg_catalog.pg_trigger_depth() > 0);

REVOKE ALL ON FUNCTION record_data_collection_settings_revision() FROM PUBLIC;
REVOKE ALL ON data_collection_settings_revisions FROM PUBLIC;

DO $$
DECLARE
    revision_writer TEXT;
BEGIN
    SELECT owner.rolname
    INTO revision_writer
    FROM pg_catalog.pg_proc AS proc
    JOIN pg_catalog.pg_roles AS owner ON owner.oid = proc.proowner
    WHERE proc.oid = 'record_data_collection_settings_revision()'::pg_catalog.regprocedure;

    IF revision_writer IN ('chronicle_app', 'chronicle_admin')
       OR EXISTS (
           SELECT 1
           FROM pg_catalog.pg_roles
           WHERE rolname = revision_writer AND NOT rolsuper AND rolbypassrls
       ) THEN
        RAISE EXCEPTION
            'DataCollection revision writer must be a distinct trusted schema owner without BYPASSRLS';
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT ON data_collection_settings_revisions TO %I',
        revision_writer
    );

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN
        IF revision_writer <> 'chronicle' THEN
            REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON data_collection_settings_revisions FROM chronicle;
        END IF;
        GRANT SELECT ON data_collection_settings_revisions TO chronicle;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON data_collection_settings_revisions FROM chronicle_app;
        GRANT SELECT ON data_collection_settings_revisions TO chronicle_app;
        EXECUTE
            'REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_app';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON data_collection_settings_revisions FROM chronicle_admin;
        GRANT SELECT ON data_collection_settings_revisions TO chronicle_admin;
        EXECUTE
            'REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_admin';
    END IF;
END $$;

COMMENT ON TABLE data_collection_settings_revisions IS
    'Immutable server-issued DataCollection revisions recorded transactionally with studies.settings for delayed consent validation.';
