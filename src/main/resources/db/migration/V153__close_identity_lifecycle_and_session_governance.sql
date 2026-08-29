-- Complete the product-owned identity lifecycle without treating the external
-- identity provider as the application's business-identity source of truth.

CREATE TABLE platform.identity_invitation (
    invitation_id uuid PRIMARY KEY,
    security_subject_id varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    token_hash char(64) NOT NULL UNIQUE,
    encrypted_delivery_payload text NOT NULL,
    delivery_address_sha256 char(64) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'PENDING',
    delivery_status varchar(20) NOT NULL DEFAULT 'QUEUED',
    expires_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    revoked_at timestamptz,
    idempotency_key varchar(160) NOT NULL,
    request_fingerprint char(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT identity_invitation_state_check CHECK (
        state IN ('PENDING','ACTIVATED','REVOKED','EXPIRED')),
    CONSTRAINT identity_invitation_delivery_status_check CHECK (
        delivery_status IN ('QUEUED','DELIVERED','FAILED')),
    CONSTRAINT identity_invitation_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT identity_invitation_activation_check CHECK (
        (state='ACTIVATED' AND activated_at IS NOT NULL)
        OR (state<>'ACTIVATED' AND activated_at IS NULL)),
    CONSTRAINT identity_invitation_revocation_check CHECK (
        (state='REVOKED' AND revoked_at IS NOT NULL)
        OR (state<>'REVOKED' AND revoked_at IS NULL)),
    CONSTRAINT identity_invitation_version_check CHECK (version >= 0),
    CONSTRAINT identity_invitation_actor_idempotency_unique
        UNIQUE (created_by,idempotency_key)
);

CREATE UNIQUE INDEX identity_invitation_one_pending_per_subject
    ON platform.identity_invitation(security_subject_id)
    WHERE state='PENDING';
CREATE INDEX identity_invitation_subject_time
    ON platform.identity_invitation(security_subject_id,created_at DESC);

CREATE TABLE platform.identity_delivery_outbox (
    event_id uuid PRIMARY KEY,
    invitation_id uuid REFERENCES platform.identity_invitation(invitation_id) ON DELETE RESTRICT,
    security_subject_id varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL,
    delivery_status varchar(20) NOT NULL DEFAULT 'QUEUED',
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    leased_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(80),
    last_error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_delivery_event_type_check CHECK (
        event_type IN ('INVITATION_DELIVERY','ACCOUNT_ENABLED','ACCOUNT_DISABLED',
                       'ACCESS_CHANGED','EMPLOYMENT_TERMINATED')),
    CONSTRAINT identity_delivery_status_check CHECK (
        delivery_status IN ('QUEUED','PROCESSING','DELIVERED','FAILED','DEAD_LETTER')),
    CONSTRAINT identity_delivery_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT identity_delivery_delivered_check CHECK (
        (delivery_status='DELIVERED' AND delivered_at IS NOT NULL)
        OR (delivery_status<>'DELIVERED' AND delivered_at IS NULL))
);

CREATE INDEX identity_delivery_outbox_claim
    ON platform.identity_delivery_outbox(available_at,created_at)
    WHERE delivery_status IN ('QUEUED','FAILED');

ALTER TABLE platform.security_user
    ADD COLUMN session_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT security_user_session_version_check CHECK (session_version >= 0);

CREATE TABLE platform.oidc_session_registry (
    session_id varchar(160) PRIMARY KEY,
    security_subject_id varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    issuer_uri varchar(500) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    provider_session_id varchar(255),
    audience varchar(255)[] NOT NULL,
    logout_authorities jsonb NOT NULL DEFAULT '{}'::jsonb,
    identity_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(80),
    CONSTRAINT oidc_session_registry_https_issuer CHECK (
        issuer_uri ~ '^https://[^[:space:]]+$'),
    CONSTRAINT oidc_session_registry_subject_not_blank CHECK (
        btrim(provider_subject) <> ''),
    CONSTRAINT oidc_session_registry_audience_not_empty CHECK (
        cardinality(audience) > 0),
    CONSTRAINT oidc_session_registry_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX oidc_session_registry_active_subject
    ON platform.oidc_session_registry(security_subject_id,created_at DESC)
    WHERE revoked_at IS NULL;
CREATE INDEX oidc_session_registry_provider_lookup
    ON platform.oidc_session_registry(issuer_uri,provider_subject,provider_session_id)
    WHERE revoked_at IS NULL;

-- Spring Session JDBC tables live in the governed schema so every application
-- node resolves the same browser session and back-channel logout can invalidate
-- a session created by another node.
CREATE TABLE platform.http_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(120),
    CONSTRAINT http_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX http_session_ix1 ON platform.http_session(session_id);
CREATE INDEX http_session_ix2 ON platform.http_session(expiry_time);
CREATE INDEX http_session_ix3 ON platform.http_session(principal_name);

CREATE TABLE platform.http_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT http_session_attributes_pk PRIMARY KEY (session_primary_id,attribute_name),
    CONSTRAINT http_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES platform.http_session(primary_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX identity_provider_binding_one_active_subject
    ON platform.identity_provider_binding(security_subject_id)
    WHERE state='ACTIVE' AND valid_until IS NULL;

GRANT INSERT,UPDATE ON TABLE platform.identity_invitation TO qiqihar_enterprise_runtime;
GRANT SELECT(invitation_id,security_subject_id,state,delivery_status,expires_at,created_by,
             idempotency_key,request_fingerprint,version,token_hash,encrypted_delivery_payload)
    ON TABLE platform.identity_invitation TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE platform.identity_delivery_outbox TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.oidc_session_registry TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.http_session TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.http_session_attributes TO qiqihar_enterprise_runtime;
GRANT INSERT,UPDATE ON TABLE platform.identity_provider_binding TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON TABLE platform.identity_invitation TO CURRENT_USER;
GRANT SELECT,INSERT,UPDATE ON TABLE platform.identity_delivery_outbox TO CURRENT_USER;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.oidc_session_registry TO CURRENT_USER;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.http_session TO CURRENT_USER;
GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE platform.http_session_attributes TO CURRENT_USER;
GRANT INSERT,UPDATE ON TABLE platform.identity_provider_binding TO CURRENT_USER;

COMMENT ON TABLE platform.identity_invitation IS
    'Single-use product invitation. Only SHA-256 token evidence and an encrypted delivery payload are persisted.';
COMMENT ON TABLE platform.identity_delivery_outbox IS
    'Retryable IdP and invitation-delivery work. QUEUED or FAILED is never presented as delivered.';
COMMENT ON TABLE platform.oidc_session_registry IS
    'Shared fail-closed session registry used for multi-instance revocation and bounded concurrency.';
