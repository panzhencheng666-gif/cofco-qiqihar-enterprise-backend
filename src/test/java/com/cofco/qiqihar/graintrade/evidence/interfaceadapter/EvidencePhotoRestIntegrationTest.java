package com.cofco.qiqihar.graintrade.evidence.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

    @AfterEach
    void clean() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        if (jdbc.sql("SELECT to_regclass('evidence.evidence_photo') IS NOT NULL")
                .query(Boolean.class).single()) {
            jdbc.sql("""
                    TRUNCATE platform.business_import_draft_evidence,
                      platform.import_job_photo,evidence.evidence_photo
                    """).update();
        }
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
