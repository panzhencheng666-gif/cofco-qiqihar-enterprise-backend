-- Keep the application's business identity independent from any OIDC provider.
-- Existing subject_id values remain unchanged; employee_number is the stable
-- enterprise identifier used to approve a provider binding during migration.

ALTER TABLE platform.security_user
    ADD COLUMN employee_number varchar(80);

UPDATE platform.security_user
SET employee_number=subject_id;

ALTER TABLE platform.security_user
    ALTER COLUMN employee_number SET NOT NULL,
    ADD CONSTRAINT security_user_employee_number_unique UNIQUE (employee_number),
    ADD CONSTRAINT security_user_employee_number_not_blank
        CHECK (btrim(employee_number) <> '');

-- Preserve the existing provisioning contract during the transition: today
-- subject_id is already the stable employee identifier. New administration
-- clients may supply a distinct employee_number explicitly when that contract
-- is introduced; older trusted paths continue to receive the same stable value.
CREATE FUNCTION platform.default_security_user_employee_number()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.employee_number IS NULL THEN
        NEW.employee_number=NEW.subject_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER security_user_default_employee_number
BEFORE INSERT ON platform.security_user
FOR EACH ROW EXECUTE FUNCTION platform.default_security_user_employee_number();

ALTER FUNCTION platform.default_security_user_employee_number()
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.default_security_user_employee_number() FROM PUBLIC;

CREATE TABLE platform.identity_provider_binding (
    binding_id uuid PRIMARY KEY,
    provider_code varchar(40) NOT NULL,
    issuer_uri varchar(500) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    security_subject_id varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    state varchar(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    approved_by varchar(120) NOT NULL
        REFERENCES platform.security_user(subject_id) ON DELETE RESTRICT,
    approved_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_provider_binding_provider_code_format
        CHECK (provider_code ~ '^[A-Z][A-Z0-9_-]{1,39}$'),
    CONSTRAINT identity_provider_binding_https_issuer
        CHECK (issuer_uri ~ '^https://[^[:space:]]+$'),
    CONSTRAINT identity_provider_binding_subject_not_blank
        CHECK (btrim(provider_subject) <> ''),
    CONSTRAINT identity_provider_binding_state_check
        CHECK (state IN ('ACTIVE','REVOKED')),
    CONSTRAINT identity_provider_binding_validity_check
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT identity_provider_binding_version_check CHECK (version >= 0),
    CONSTRAINT identity_provider_binding_exact_identity_unique
        UNIQUE (issuer_uri,provider_subject)
);

CREATE INDEX identity_provider_binding_active_lookup
    ON platform.identity_provider_binding
       (issuer_uri,provider_subject,valid_from,valid_until)
    WHERE state='ACTIVE';

ALTER TABLE platform.identity_provider_binding OWNER TO qiqihar_migration_owner;
REVOKE ALL ON TABLE platform.identity_provider_binding FROM PUBLIC;
GRANT SELECT ON TABLE platform.identity_provider_binding TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE platform.identity_provider_binding TO CURRENT_USER;

COMMENT ON COLUMN platform.security_user.employee_number IS
    'Stable enterprise employee identifier; never derived from an OIDC provider subject.';
COMMENT ON TABLE platform.identity_provider_binding IS
    'Administratively approved exact OIDC issuer and subject mapping to a stable business identity.';
