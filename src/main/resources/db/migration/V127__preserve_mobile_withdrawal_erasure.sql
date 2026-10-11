-- Installed mobile clients confirm withdrawal and erasure without negotiating retention.
UPDATE studies
SET settings = jsonb_set(COALESCE(settings, '{}'::jsonb), '{Withdrawal}',
    jsonb_build_object('@class', 'com.openlattice.chronicle.study.StudyWithdrawalSetting', 'mode', 'ERASE'))
WHERE settings -> 'Withdrawal' ->> 'mode' IS DISTINCT FROM 'ERASE';
