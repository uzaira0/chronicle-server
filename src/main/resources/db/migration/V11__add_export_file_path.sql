-- V11: Add file_path column to export_jobs for file-based download
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS file_path TEXT;
