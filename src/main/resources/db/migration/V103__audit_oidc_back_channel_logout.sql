ALTER TABLE platform.security_session_audit_event
    DROP CONSTRAINT security_session_audit_event_action_code_check;

ALTER TABLE platform.security_session_audit_event
    ADD CONSTRAINT security_session_audit_event_action_code_check CHECK (action_code IN (
        'LOGIN_SUCCESS','LOGIN_DENIED','LOGOUT','OIDC_BACK_CHANNEL_LOGOUT',
        'SESSION_EXPIRED','SESSION_ACCESS_DENIED'));

COMMENT ON TABLE platform.security_session_audit_event IS
    'Append-only login, user logout, OIDC back-channel logout, expiry and access-denial evidence. Raw session identifiers are never stored.';
