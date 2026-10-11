-- The transactional audit is written before the status change commits. HTTP audit_logs
-- are written afterwards, and participant.updated_at also changes on annotation edits;
-- neither is a safe upper bound for the real stop. Use the first stop in the current
-- audited enrollment interval, not a later PAUSED -> COLLECTION_COMPLETED transition.
-- Only legacy raw-id descriptions bind a stop to one participant. Keyed fingerprints and
-- study-wide duration pauses cannot, so they are not evidence for any one participant:
-- a too-early cutoff discards and acknowledges valid backlog, a too-late one admits
-- post-stop captures. Without bound evidence the cutoff stays NULL and the collection
-- gate holds that participant's queued uploads unacknowledged on the device.
WITH status_events AS (
    SELECT p.study_id, p.participant_id, a.event_timestamp,
           substring(a.description FROM ' to (ENROLLED|PAUSED|COLLECTION_COMPLETED)$') AS status
    FROM study_participants p
    -- Legacy individual events left audit.study_id unset; the exact description binds both IDs.
    JOIN audit a ON a.audit_event_type = 'UPDATE_PARTICIPATION_STATUS'
      AND a.description IN (
          'Set participation status of participant ' || p.participant_id || ' in study ' || p.study_id || ' to ENROLLED',
          'Set participation status of participant ' || p.participant_id || ' in study ' || p.study_id || ' to PAUSED',
          'Set participation status of participant ' || p.participant_id || ' in study ' || p.study_id || ' to COLLECTION_COMPLETED'
      )
    WHERE p.participation_status IN ('PAUSED', 'COLLECTION_COMPLETED') AND p.capture_cutoff IS NULL
), enrollment AS (
    SELECT study_id, participant_id, max(event_timestamp) FILTER (WHERE status = 'ENROLLED') AS last_enrolled
    FROM status_events GROUP BY study_id, participant_id
), stops AS (
    SELECT e.study_id, e.participant_id, min(e.event_timestamp) AS cutoff
    FROM status_events e LEFT JOIN enrollment n USING (study_id, participant_id)
    WHERE e.status IN ('PAUSED', 'COLLECTION_COMPLETED')
      AND (n.last_enrolled IS NULL OR e.event_timestamp >= n.last_enrolled)
    GROUP BY e.study_id, e.participant_id
)
UPDATE study_participants p
SET capture_cutoff = stops.cutoff
FROM stops
WHERE p.study_id = stops.study_id AND p.participant_id = stops.participant_id
  AND p.participation_status IN ('PAUSED', 'COLLECTION_COMPLETED') AND p.capture_cutoff IS NULL;
