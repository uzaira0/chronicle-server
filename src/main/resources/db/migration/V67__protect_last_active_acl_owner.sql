-- V67: make the permanent-owner invariant authoritative in PostgreSQL.
--
-- Service-level checks cannot cover direct SQL, Hazelcast MapStore execution,
-- or account deletion. The deferred trigger evaluates the final transaction
-- state and serializes competing owner removals for the same ACL. Requiring one
-- non-expiring owner also prevents a finite grant from silently orphaning an
-- ACL merely because time advances without another write.

ALTER TABLE securable_objects
    ADD COLUMN IF NOT EXISTS acl_owner_fence BIGINT NOT NULL DEFAULT 0;

-- Hold both write surfaces through validation and trigger installation. This
-- closes the old-binary/direct-writer window between the preflight and DDL.
LOCK TABLE securable_objects, permissions IN SHARE ROW EXCLUSIVE MODE;

/*
 * The two built-in audience roles are intentionally self-owned by
 * AuthorizationInitializationTask. Treat those owners as permanent only on
 * the matching built-in Role object itself. In particular, neither role may
 * satisfy the owner invariant for a study or any other ACL.
 */
CREATE OR REPLACE FUNCTION chronicle_is_eligible_permanent_acl_owner(
    target_acl_key UUID[],
    target_principal_type TEXT,
    target_principal_id TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT
        target_principal_type = 'USER'
        OR (
            target_principal_type = 'ROLE'
            AND target_principal_id = 'admin'
        )
        OR (
            target_principal_type = 'ROLE'
            AND target_principal_id IN ('AuthenticatedUser', 'AnonymousUser')
            AND cardinality(target_acl_key) = 2
            AND target_acl_key[1] = '00000000-0000-0000-0000-000000000002'::uuid
            AND EXISTS (
                SELECT 1
                FROM securable_objects AS bootstrap_object
                WHERE bootstrap_object.acl_key = target_acl_key
                  AND bootstrap_object.securable_object_type = 'Role'
                  AND bootstrap_object.name = target_principal_id
            )
        )
$$;

DO $$
DECLARE
    invalid_acl_count BIGINT;
    invalid_acl_samples TEXT;
BEGIN
    SELECT count(*)
    INTO invalid_acl_count
    FROM securable_objects AS object
    WHERE EXISTS (
        SELECT 1
        FROM permissions AS any_permission
        WHERE any_permission.acl_key = object.acl_key
          AND cardinality(any_permission.permissions) > 0
    )
      AND NOT EXISTS (
        SELECT 1
        FROM permissions AS permission
        WHERE permission.acl_key = object.acl_key
          AND 'OWNER' = ANY(permission.permissions)
          AND permission.expiration_date = 'infinity'::timestamptz
          AND chronicle_is_eligible_permanent_acl_owner(
              permission.acl_key,
              permission.principal_type,
              permission.principal_id
          )
    );

    IF invalid_acl_count > 0 THEN
        SELECT string_agg(array_to_string(sample.acl_key, ','), '; ')
        INTO invalid_acl_samples
        FROM (
            SELECT object.acl_key
            FROM securable_objects AS object
            WHERE EXISTS (
                SELECT 1 FROM permissions AS any_permission
                WHERE any_permission.acl_key = object.acl_key
                  AND cardinality(any_permission.permissions) > 0
            )
              AND NOT EXISTS (
                SELECT 1
                FROM permissions AS permission
                WHERE permission.acl_key = object.acl_key
                  AND 'OWNER' = ANY(permission.permissions)
                  AND permission.expiration_date = 'infinity'::timestamptz
                  AND chronicle_is_eligible_permanent_acl_owner(
                      permission.acl_key,
                      permission.principal_type,
                      permission.principal_id
                  )
            )
            ORDER BY object.acl_key
            LIMIT 10
        ) AS sample;
        RAISE EXCEPTION
            'Cannot install permanent-owner constraint: % permission-bearing ACLs lack an eligible non-expiring owner',
            invalid_acl_count
            USING ERRCODE = '23514',
                  DETAIL = 'Sample ACL keys: ' || COALESCE(invalid_acl_samples, '[none]'),
                  HINT = 'Assign a non-expiring USER or admin ROLE owner to every reported ACL, then rerun the migration.';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION chronicle_assert_acl_permanent_owner(target_acl_key UUID[])
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    -- Deleting a securable object intentionally deletes its whole ACL.
    IF NOT EXISTS (
        SELECT 1
        FROM securable_objects
        WHERE acl_key = target_acl_key
    ) THEN
        RETURN;
    END IF;

    -- A reserved object with no ACEs is inert. The first permission-bearing
    -- transaction must establish a permanent owner before it can commit.
    IF NOT EXISTS (
        SELECT 1
        FROM permissions
        WHERE acl_key = target_acl_key
          AND cardinality(permissions) > 0
    ) THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM permissions
        WHERE acl_key = target_acl_key
          AND 'OWNER' = ANY(permissions)
          AND expiration_date = 'infinity'::timestamptz
          AND chronicle_is_eligible_permanent_acl_owner(
              acl_key,
              principal_type,
              principal_id
          )
    ) THEN
        RAISE EXCEPTION
            'ACL % must retain an eligible non-expiring owner',
            target_acl_key
            USING ERRCODE = '23514',
                  CONSTRAINT = 'permissions_permanent_owner';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION chronicle_lock_acl_owner_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_acl_key UUID[];
    new_acl_key UUID[];
BEGIN
    IF TG_OP <> 'INSERT'
        AND 'OWNER' = ANY(OLD.permissions)
        AND chronicle_is_eligible_permanent_acl_owner(
            OLD.acl_key,
            OLD.principal_type,
            OLD.principal_id
        )
    THEN
        old_acl_key := OLD.acl_key;
    END IF;

    IF TG_OP <> 'DELETE'
        AND 'OWNER' = ANY(NEW.permissions)
        AND chronicle_is_eligible_permanent_acl_owner(
            NEW.acl_key,
            NEW.principal_type,
            NEW.principal_id
        )
    THEN
        new_acl_key := NEW.acl_key;
    END IF;

    /*
     * Mutate a real fencing row before the ACE write. Under READ COMMITTED this
     * serializes and refreshes the later constraint check; under REPEATABLE READ
     * or SERIALIZABLE a waiter whose snapshot predates the other mutation gets a
     * serialization failure instead of committing against a stale snapshot.
     */
    IF old_acl_key IS NOT NULL AND new_acl_key IS NOT NULL THEN
        IF array_to_string(old_acl_key, ',') <= array_to_string(new_acl_key, ',') THEN
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = old_acl_key;
            IF old_acl_key <> new_acl_key THEN
                UPDATE securable_objects
                SET acl_owner_fence = acl_owner_fence + 1
                WHERE acl_key = new_acl_key;
            END IF;
        ELSE
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = new_acl_key;
            UPDATE securable_objects
            SET acl_owner_fence = acl_owner_fence + 1
            WHERE acl_key = old_acl_key;
        END IF;
    ELSIF old_acl_key IS NOT NULL THEN
        UPDATE securable_objects
        SET acl_owner_fence = acl_owner_fence + 1
        WHERE acl_key = old_acl_key;
    ELSIF new_acl_key IS NOT NULL THEN
        UPDATE securable_objects
        SET acl_owner_fence = acl_owner_fence + 1
        WHERE acl_key = new_acl_key;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION chronicle_protect_securable_object_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM chronicle_assert_acl_permanent_owner(NEW.acl_key);
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION chronicle_protect_last_active_acl_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Every permission mutation is checked. Restricting this trigger to rows
    -- that already looked like eligible owners allowed a first ineligible ACE
    -- (including a ROLE owner on an arbitrary ACL) to bypass the invariant.
    IF TG_OP <> 'INSERT' THEN
        PERFORM chronicle_assert_acl_permanent_owner(OLD.acl_key);
    END IF;

    IF TG_OP = 'INSERT'
        OR (TG_OP = 'UPDATE' AND NEW.acl_key IS DISTINCT FROM OLD.acl_key)
    THEN
        PERFORM chronicle_assert_acl_permanent_owner(NEW.acl_key);
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS permissions_last_active_owner ON permissions;
DROP TRIGGER IF EXISTS permissions_permanent_owner ON permissions;
DROP TRIGGER IF EXISTS permissions_owner_mutation_lock ON permissions;
DROP TRIGGER IF EXISTS securable_object_permanent_owner ON securable_objects;

CREATE TRIGGER permissions_owner_mutation_lock
BEFORE INSERT OR UPDATE OR DELETE ON permissions
FOR EACH ROW
EXECUTE FUNCTION chronicle_lock_acl_owner_mutation();

CREATE CONSTRAINT TRIGGER permissions_permanent_owner
AFTER INSERT OR UPDATE OR DELETE ON permissions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION chronicle_protect_last_active_acl_owner();

CREATE CONSTRAINT TRIGGER securable_object_permanent_owner
AFTER INSERT OR UPDATE ON securable_objects
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION chronicle_protect_securable_object_owner();
