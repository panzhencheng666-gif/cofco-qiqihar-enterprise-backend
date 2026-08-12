DROP VIEW evidence.evidence_photo_consistency;

ALTER TABLE evidence.evidence_photo
    DROP CONSTRAINT evidence_photo_content_digest_check,
    ALTER COLUMN watermarked_sha256 DROP EXPRESSION,
    ADD COLUMN content_storage_code varchar(20) NOT NULL DEFAULT 'DATABASE',
    ADD COLUMN content_object_key varchar(160),
    ALTER COLUMN original_bytes DROP NOT NULL,
    ALTER COLUMN watermarked_bytes DROP NOT NULL;

ALTER TABLE evidence.evidence_photo
    DROP CONSTRAINT evidence_photo_original_bytes_check,
    DROP CONSTRAINT evidence_photo_watermarked_bytes_check,
    ADD CONSTRAINT evidence_photo_content_storage_check CHECK (
        (content_storage_code = 'DATABASE'
            AND original_bytes IS NOT NULL AND octet_length(original_bytes) > 0
            AND watermarked_bytes IS NOT NULL AND octet_length(watermarked_bytes) > 0
            AND octet_length(original_bytes) = byte_length
            AND content_object_key IS NULL)
        OR
        (content_storage_code = 'EXTERNAL'
            AND original_bytes IS NULL AND watermarked_bytes IS NULL
            AND content_object_key ~ '^evidence/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]evp$')
    ),
    ADD CONSTRAINT evidence_photo_content_digest_check CHECK (
        content_storage_code = 'EXTERNAL'
        OR (encode(sha256(original_bytes),'hex') = btrim(sha256)
            AND encode(sha256(watermarked_bytes),'hex') = btrim(watermarked_sha256))
    );

CREATE FUNCTION evidence.derive_database_watermarked_digest()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.content_storage_code = 'DATABASE'
       AND NEW.watermarked_bytes IS NOT NULL
       AND (TG_OP = 'INSERT'
            OR NEW.watermarked_bytes IS DISTINCT FROM OLD.watermarked_bytes
            OR NEW.watermarked_sha256 IS NULL) THEN
        NEW.watermarked_sha256 := encode(sha256(NEW.watermarked_bytes),'hex');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER evidence_photo_database_digest
BEFORE INSERT OR UPDATE OF watermarked_bytes,content_storage_code
ON evidence.evidence_photo
FOR EACH ROW EXECUTE FUNCTION evidence.derive_database_watermarked_digest();

CREATE VIEW evidence.evidence_photo_consistency AS
SELECT photo.photo_id,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN octet_length(photo.original_bytes)=photo.byte_length END AS original_length_matches,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN encode(sha256(photo.original_bytes),'hex')=btrim(photo.sha256) END AS original_digest_matches,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN encode(sha256(photo.watermarked_bytes),'hex')=btrim(photo.watermarked_sha256)
         END AS watermarked_digest_matches,
       CASE
         WHEN photo.state_code='STAGED' THEN photo.attached_domain IS NULL
           AND photo.attached_record_id IS NULL AND photo.attached_region_code IS NULL
         WHEN photo.attached_domain='PRODUCTION' THEN EXISTS(
           SELECT 1 FROM production.production_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         WHEN photo.attached_domain='MARKET' THEN EXISTS(
           SELECT 1 FROM market.market_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         ELSE false
       END AS attachment_reference_matches,
       CASE
         WHEN photo.content_storage_code='EXTERNAL' THEN 'EXTERNAL_VERIFICATION_REQUIRED'
         WHEN octet_length(photo.original_bytes)=photo.byte_length
          AND encode(sha256(photo.original_bytes),'hex')=btrim(photo.sha256)
          AND encode(sha256(photo.watermarked_bytes),'hex')=btrim(photo.watermarked_sha256)
          AND (photo.state_code='STAGED'
            OR (photo.attached_domain='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.record_id=photo.attached_record_id
                AND record.region_code=photo.attached_region_code))
            OR (photo.attached_domain='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.record_id=photo.attached_record_id
                AND record.region_code=photo.attached_region_code)))
         THEN 'CONSISTENT' ELSE 'INCONSISTENT' END AS consistency_state
FROM evidence.evidence_photo photo;

COMMENT ON COLUMN evidence.evidence_photo.content_storage_code IS
    'DATABASE preserves legacy private bytea rows; EXTERNAL stores only a generated private object locator.';

COMMENT ON COLUMN evidence.evidence_photo.content_object_key IS
    'Generated private content key. It is never returned by the public API and never represents a public URL.';

COMMENT ON TABLE evidence.evidence_photo IS
    'Private evidence metadata and authorization; content may be legacy database bytes or a private external object.';

COMMENT ON FUNCTION evidence.derive_database_watermarked_digest() IS
    'Preserves the legacy generated-digest behavior for DATABASE rows only; EXTERNAL content must provide its verified object digest explicitly.';
