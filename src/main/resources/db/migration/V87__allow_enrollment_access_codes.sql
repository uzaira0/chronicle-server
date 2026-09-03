ALTER TABLE participant_form_access_codes
    DROP CONSTRAINT participant_form_access_codes_form_kind_check;

ALTER TABLE participant_form_access_codes
    ADD CONSTRAINT participant_form_access_codes_form_kind_check
    CHECK (form_kind IN ('APP_USAGE', 'QUESTIONNAIRE', 'TIME_USE_DIARY', 'PORTAL', 'ENROLLMENT'));
