package com.cofco.qiqihar.graintrade.evidence.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportPhotoPackage;
import com.cofco.qiqihar.graintrade.shared.security.application.InternalSecuritySubjectScope;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class EvidencePhotoRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired BusinessImportPhotoPackage importPhotos;
    @Autowired InternalSecuritySubjectScope subjects;

    @AfterEach
    void clean() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        if (jdbc.sql("SELECT to_regclass('evidence.evidence_photo') IS NOT NULL")
                .query(Boolean.class).single()) {
            jdbc.sql("""
                    TRUNCATE platform.business_import_draft_evidence,
                      platform.import_job_photo,evidence.evidence_photo
                    """).update();
            jdbc.sql("DELETE FROM platform.import_job WHERE idempotency_key LIKE 'photo-package-%'").update();
        }
    }

    @Test
    void stagesImportPhotosWithoutInventingCaptureLocationAndSkipsCorruptFilesAsWarnings() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,
                  source_content,requested_by,work_unit_code,status_code,created_at,completed_at,attempt_count)
                SELECT :jobId,'PRODUCTION',:key,repeat('a',64),'photo package test',subject_id,
                  work_unit_code,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1
                FROM platform.security_user WHERE subject_id='production-tester'
                """).param("jobId", jobId).param("key", "photo-package-" + jobId).update();

        var staged = subjects.callAs("production-tester", () -> importPhotos.stage(jobId, List.of(
                new BusinessImportPhotoPackage.PhotoPart("齐齐哈尔样本.png", "image/png", uncheckedPngBytes()),
                new BusinessImportPhotoPackage.PhotoPart("损坏照片.png", "image/png", "not-image".getBytes()),
                new BusinessImportPhotoPackage.PhotoPart("伪装照片.jpg", "image/jpeg", uncheckedPngBytes()),
                new BusinessImportPhotoPackage.PhotoPart("过大照片.png", "image/png", new byte[10 * 1024 * 1024 + 1]),
                new BusinessImportPhotoPackage.PhotoPart(
                        "超大像素照片.png", "image/png", oversizedDimensionsPng())),
                "产情 | 齐齐哈尔样本"));

        assertThat(staged.stagedPhotoIds()).hasSize(1);
        assertThat(staged.warnings()).hasSize(4).allSatisfy(warning ->
                assertThat(warning.code()).isEqualTo("INVALID_EVIDENCE_PHOTO"));
        assertThat(jdbc.sql("""
                SELECT (photo.captured_at IS NULL) AND (photo.capture_latitude IS NULL)
                  AND (photo.capture_longitude IS NULL)
                  AND photo.watermark_text LIKE '%定位待补充%'
                  AND mapping.normalized_filename='齐齐哈尔样本.png'
                FROM evidence.evidence_photo photo
                JOIN platform.import_job_photo mapping ON mapping.photo_id=photo.photo_id
                WHERE mapping.import_job_id=:jobId
                """).param("jobId", jobId).query(Boolean.class).single()).isTrue();
        assertThat(importPhotos.resolve(jobId, "齐齐哈尔样本.png"))
                .containsExactly(staged.stagedPhotoIds().getFirst());
    }

    @Test
    void keepsStagedPhotoPrivateThenSharesAttachedPhotoWithAuthorizedColleagues() throws Exception {
        byte[] original = pngBytes();
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "field.png", "image/png", original))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543")
                        .param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 现场采集")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("STAGED"))
                .andExpect(jsonPath("$.data.sha256").isString())
                .andReturn().getResponse().getContentAsString();
        String photoId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        byte[] watermarked = mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(watermarked).isNotEqualTo(original);
        assertContentReadAudit(photoId, "production-tester", 1);

        mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_PHOTO_ACCESS_DENIED"));
        assertContentReadAudit(photoId, "production-tester", 1);

        JdbcClient.create(dataSource).sql("""
                UPDATE evidence.evidence_photo
                SET state_code='ATTACHED',attached_domain='PRODUCTION',
                    attached_record_id='PROD-001',attached_region_code='230200'
                WHERE photo_id=CAST(:id AS uuid)
                """).param("id", photoId).update();

        mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk());
        assertContentReadAudit(photoId, "market-tester", 1);
        assertThat(contentReadAuditCount(photoId)).isEqualTo(2);
    }

    @Test
    void rejectsNonImageWithoutPersistence() throws Exception {
        mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", "not-an-image".getBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543")
                        .param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 现场采集")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EVIDENCE_PHOTO"));

        JdbcClient jdbc = JdbcClient.create(dataSource);
        if (jdbc.sql("SELECT to_regclass('evidence.evidence_photo') IS NOT NULL")
                .query(Boolean.class).single()) {
            assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo")
                    .query(Long.class).single()).isZero();
        }
    }

    @Test
    void rejectsBlankWatermarkWithoutPersistence() throws Exception {
        mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "field.png", "image/png", pngBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543")
                        .param("longitude", "123.9182")
                        .param("watermarkText", " ")
                        .principal(() -> "production-tester"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EVIDENCE_PHOTO"));

        assertThat(JdbcClient.create(dataSource).sql("SELECT count(*) FROM evidence.evidence_photo")
                .query(Long.class).single()).isZero();
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(40, 120, 80));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] uncheckedPngBytes() {
        try {
            return pngBytes();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] oversizedDimensionsPng() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(output);
            data.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            byte[] ihdr = new byte[] {
                0, 0, 31, 64, 0, 0, 23, 112, 8, 2, 0, 0, 0
            };
            writePngChunk(data, "IHDR", ihdr);
            writePngChunk(data, "IEND", new byte[0]);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writePngChunk(DataOutputStream data, String type, byte[] payload) throws Exception {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        data.writeInt(payload.length);
        data.write(typeBytes);
        data.write(payload);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(payload);
        data.writeInt((int) crc.getValue());
    }

    private void assertContentReadAudit(String photoId, String actor, long expected) {
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='EVIDENCE_PHOTO' AND aggregate_id=:id
                  AND action_code='EVIDENCE_PHOTO_CONTENT_READ' AND actor_subject_id=:actor
                """).param("id", photoId).param("actor", actor).query(Long.class).single())
                .isEqualTo(expected);
    }

    private long contentReadAuditCount(String photoId) {
        return JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='EVIDENCE_PHOTO' AND aggregate_id=:id
                  AND action_code='EVIDENCE_PHOTO_CONTENT_READ'
                """).param("id", photoId).query(Long.class).single();
    }
}
