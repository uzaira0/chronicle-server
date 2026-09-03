ALTER TABLE study_participants ADD COLUMN IF NOT EXISTS participant_notes TEXT;
ALTER TABLE study_participants ADD COLUMN IF NOT EXISTS participant_tags TEXT[] DEFAULT '{}';
ALTER TABLE study_participants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();
