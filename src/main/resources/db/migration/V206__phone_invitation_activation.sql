-- Phone invitations reuse the invitation lifecycle without creating delivery events.
-- Only the hash is needed until SMS verification establishes the phone binding.
ALTER TABLE platform.identity_invitation
    ADD COLUMN activation_phone_sha256 char(64),
    ALTER COLUMN delivery_status TYPE varchar(32);
ALTER TABLE platform.identity_invitation DROP CONSTRAINT identity_invitation_delivery_status_check;
ALTER TABLE platform.identity_invitation ADD CONSTRAINT identity_invitation_delivery_status_check
    CHECK ((activation_phone_sha256 IS NULL AND delivery_status IN ('QUEUED','DELIVERED','FAILED'))
        OR (activation_phone_sha256 IS NOT NULL AND activation_phone_sha256 ~ '^[0-9a-f]{64}$' AND delivery_status='AWAITING_VERIFICATION'));
CREATE UNIQUE INDEX identity_invitation_one_pending_per_phone
    ON platform.identity_invitation(activation_phone_sha256)
    WHERE activation_phone_sha256 IS NOT NULL AND state='PENDING';
GRANT SELECT(activation_phone_sha256) ON platform.identity_invitation TO qiqihar_enterprise_runtime;
