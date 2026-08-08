package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoRepository;
import com.cofco.qiqihar.graintrade.evidence.application.EvidencePhotoView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvidencePhotoRepository implements EvidencePhotoRepository {
    private final JdbcClient jdbc;

    public JdbcEvidencePhotoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EvidencePhotoView insert(EvidencePhotoUpload upload) {
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED',:filename,:mediaType,:original,:watermarked,:length,:sha256,
                  :capturedAt,CAST(:latitude AS numeric),CAST(:longitude AS numeric),:watermark,:uploadedBy,:uploadedAt)
                """).param("id", upload.id()).param("filename", upload.filename())
                .param("mediaType", upload.mediaType()).param("original", upload.originalBytes())
                .param("watermarked", upload.watermarkedBytes()).param("length", upload.byteLength())
                .param("sha256", upload.sha256()).param("capturedAt", upload.capturedAt())
                .param("latitude", upload.latitude()).param("longitude", upload.longitude())
                .param("watermark", upload.watermarkText()).param("uploadedBy", upload.uploadedBy())
                .param("uploadedAt", upload.uploadedAt()).update();
        return find(upload.id()).orElseThrow().view();
    }

    @Override
    public Optional<StoredEvidencePhoto> find(UUID id) {
        return jdbc.sql("""
                SELECT photo_id,state_code,original_filename,media_type,original_bytes,watermarked_bytes,
                  byte_length,sha256,captured_at,capture_latitude::text,capture_longitude::text,
                  watermark_text,uploaded_by,uploaded_at,attached_domain,attached_record_id,attached_region_code
                FROM evidence.evidence_photo WHERE photo_id=:id
                """).param("id", id).query((row, index) -> new StoredEvidencePhoto(
                        view(row), row.getBytes("original_bytes"), row.getBytes("watermarked_bytes"),
                        row.getString("attached_region_code"))).optional();
    }

    @Override
    public List<EvidencePhotoView> findAttached(String domain, String recordId) {
        return jdbc.sql("""
                SELECT photo_id,state_code,original_filename,media_type,byte_length,sha256,captured_at,
                  capture_latitude::text,capture_longitude::text,watermark_text,uploaded_by,uploaded_at,
                  attached_domain,attached_record_id
                FROM evidence.evidence_photo
                WHERE state_code='ATTACHED' AND attached_domain=:domain AND attached_record_id=:recordId
                ORDER BY uploaded_at,photo_id
                """).param("domain", domain).param("recordId", recordId)
                .query((row, index) -> view(row)).list();
    }

    @Override
    public boolean attach(UUID id, String domain, String recordId, String regionCode, String subjectId) {
        return jdbc.sql("""
                UPDATE evidence.evidence_photo
                SET state_code='ATTACHED',attached_domain=:domain,attached_record_id=:recordId,
                  attached_region_code=:regionCode
                WHERE photo_id=:id AND state_code='STAGED' AND uploaded_by=:subject
                """).param("id", id).param("domain", domain).param("recordId", recordId)
                .param("regionCode", regionCode).param("subject", subjectId).update() == 1;
    }

    private static EvidencePhotoView view(java.sql.ResultSet row) throws java.sql.SQLException {
        return new EvidencePhotoView(row.getObject("photo_id", UUID.class), row.getString("state_code"),
                row.getString("original_filename"), row.getString("media_type"), row.getLong("byte_length"),
                row.getString("sha256").trim(), row.getObject("captured_at", java.time.OffsetDateTime.class),
                row.getString("capture_latitude"), row.getString("capture_longitude"),
                row.getString("watermark_text"), row.getString("uploaded_by"),
                row.getObject("uploaded_at", java.time.OffsetDateTime.class), row.getString("attached_domain"),
                row.getString("attached_record_id"));
    }
}
