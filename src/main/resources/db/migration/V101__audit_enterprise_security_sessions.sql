CREATE TABLE platform.security_session_audit_event (
    event_id uuid PRIMARY KEY,
    subject_id varchar(120),
    session_hash char(64),
    action_code varchar(40) NOT NULL CHECK (action_code IN (
        'LOGIN_SUCCESS','LOGIN_DENIED','LOGOUT','SESSION_EXPIRED','SESSION_ACCESS_DENIED')),
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(session_hash,action_code)
);

CREATE INDEX security_session_audit_subject_time
    ON platform.security_session_audit_event(subject_id,occurred_at DESC);

CREATE FUNCTION platform.reject_security_session_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'security session audit events are immutable';
END;
$$;

CREATE TRIGGER security_session_audit_immutable
BEFORE UPDATE OR DELETE ON platform.security_session_audit_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_security_session_audit_mutation();

COMMENT ON TABLE platform.security_session_audit_event IS
    'Append-only login, logout, expiry and access-denial evidence. Raw session identifiers are never stored.';
