-- Export downloads are authorized by authenticated user, CSRF, EXPORT_DATA permission,
-- and export creator ownership. The legacy download_token value was a recoverable
-- bearer secret stored in export_jobs and is no longer used.
UPDATE export_jobs
SET download_token = NULL
WHERE download_token IS NOT NULL;
