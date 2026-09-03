CREATE TABLE IF NOT EXISTS data_quality_alerts (
    alert_id UUID NOT NULL PRIMARY KEY,
    study_id UUID NOT NULL,
    participant_id TEXT NOT NULL,
    alert_type TEXT NOT NULL,
    message TEXT NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_data_quality_alerts_study_id ON data_quality_alerts (study_id);
CREATE INDEX IF NOT EXISTS idx_data_quality_alerts_created_at ON data_quality_alerts (created_at);
