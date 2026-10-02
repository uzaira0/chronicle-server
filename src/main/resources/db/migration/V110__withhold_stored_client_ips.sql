-- Client IP addresses are no longer stored for participants, and staff IPs are stored only as
-- keyed references (ClientIpRecord). Rows written before this release hold either an unkeyed
-- SHA-256 reference (audit_logs), which is reversible by hashing every IPv4 address, or the raw
-- address (study_settings_audit, refresh_tokens). None of them can be re-keyed, so all are
-- withheld.
--
-- audit_logs and study_settings_audit forbid UPDATE through RLS (USING false, forced on the
-- owner too) and V25 revoked UPDATE on study_settings_audit. The owner grants itself UPDATE and
-- adds a permissive policy (UPDATE ... WHERE also needs the row visible) for this scrub only, then removes both; a superuser needs neither.

GRANT UPDATE ON audit_logs, study_settings_audit TO CURRENT_USER;

CREATE POLICY withhold_client_ip ON audit_logs FOR ALL USING (true) WITH CHECK (true);
UPDATE audit_logs SET ip_address = 'ip:[withheld]' WHERE ip_address <> 'ip:[withheld]';
DROP POLICY withhold_client_ip ON audit_logs;

CREATE POLICY withhold_client_ip ON study_settings_audit FOR ALL USING (true) WITH CHECK (true);
UPDATE study_settings_audit SET source_ip = NULL WHERE source_ip IS NOT NULL;
DROP POLICY withhold_client_ip ON study_settings_audit;

CREATE POLICY withhold_client_ip ON refresh_tokens FOR ALL USING (true) WITH CHECK (true);
UPDATE refresh_tokens SET ip_address = NULL WHERE ip_address IS NOT NULL;
DROP POLICY withhold_client_ip ON refresh_tokens;

REVOKE UPDATE ON audit_logs, study_settings_audit FROM CURRENT_USER;
