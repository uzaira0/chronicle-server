CREATE TABLE fixture_upload_outbox_fault (study_id uuid PRIMARY KEY, fail_insert boolean NOT NULL);
GRANT SELECT ON fixture_upload_outbox_fault TO chronicle_app;
CREATE FUNCTION fixture_upload_outbox_fault() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fail boolean;
BEGIN
  SELECT fail_insert INTO fail FROM fixture_upload_outbox_fault WHERE study_id = NEW.study_id;
  IF fail IS TRUE THEN RAISE EXCEPTION 'Fixture outbox insertion refused'; END IF;
  IF fail IS FALSE THEN NEW.available_at := 'infinity'::timestamptz; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER fixture_upload_outbox_fault BEFORE INSERT ON webhook_deliveries
FOR EACH ROW EXECUTE FUNCTION fixture_upload_outbox_fault();
