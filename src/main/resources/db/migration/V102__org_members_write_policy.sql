-- V102: Split the organization_members policy so a caller cannot self-grant membership.
--
-- V57's single FOR ALL policy supplied only a USING clause. PostgreSQL then reuses that
-- expression as the INSERT/UPDATE WITH CHECK, and the expression admits any row whose
-- user_id equals app.current_user_id. Any authenticated caller could therefore insert
-- (arbitrary_organization_id, own_user_id, 'OWNER') and take over an organization.
--
-- Reads keep the self-row clause (a member must be able to see their own membership).
-- Writes require platform admin context or an organization the caller is authorized on.
-- The organization role itself is enforced above the database by
-- @RequiresOrganizationAccess / OrganizationAuthorizationAspect.

DROP POLICY IF EXISTS org_members_policy ON organization_members;
DROP POLICY IF EXISTS org_members_read_policy ON organization_members;
DROP POLICY IF EXISTS org_members_insert_policy ON organization_members;
DROP POLICY IF EXISTS org_members_update_policy ON organization_members;
DROP POLICY IF EXISTS org_members_delete_policy ON organization_members;

CREATE POLICY org_members_read_policy ON organization_members
    FOR SELECT USING (
        current_setting('app.is_admin', true) = 'true'
        OR user_id = current_setting('app.current_user_id', true)
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );

CREATE POLICY org_members_insert_policy ON organization_members
    FOR INSERT WITH CHECK (
        current_setting('app.is_admin', true) = 'true'
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );

CREATE POLICY org_members_update_policy ON organization_members
    FOR UPDATE
    USING (
        current_setting('app.is_admin', true) = 'true'
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    )
    WITH CHECK (
        current_setting('app.is_admin', true) = 'true'
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );

CREATE POLICY org_members_delete_policy ON organization_members
    FOR DELETE USING (
        current_setting('app.is_admin', true) = 'true'
        OR organization_id::text = ANY(string_to_array(current_setting('app.authorized_orgs', true), ','))
    );
