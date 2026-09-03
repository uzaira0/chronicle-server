-- V61 left collected_at NOT NULL with no default, so a backend built before the column existed
-- could not insert usage events once the migration applied ahead of its restart (observed live
-- 2026-07-15: the buffer-to-storage move task wedged until the default was applied out of band).
-- Receipt-time default keeps pre-collected_at writers working; current code binds it explicitly,
-- and fresh installs create the column with this default from the table definition.
ALTER TABLE chronicle_usage_events
    ALTER COLUMN collected_at SET DEFAULT now();
