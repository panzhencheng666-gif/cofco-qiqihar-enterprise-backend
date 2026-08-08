package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class ProductionEvidenceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @AfterEach
    void clean() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.business_audit_event,production.production_record,
                  evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void requiresAtLeastOnePhotoAndAttachesItAtomically() throws Exception {
        mvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EVIDENCE_PHOTO"));

        String photoId = upload("production-tester");
        String response = mvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body(photoId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.evidencePhotos[0].id").value(photoId))
                .andReturn().getResponse().getContentAsString();
        String recordId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT attached_record_id FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)")
                .param("id", photoId).query(String.class).single()).isEqualTo(recordId);

        mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                        .principal(() -> "market-tester"))
                .andExpect(status().isOk());
    }

    @Test
    void unavailablePhotoRollsBackTheProductionRecordAndValidPhotoAttachment() throws Exception {
        String available = upload("production-tester");
        String unavailable = upload("market-tester");

        mvc.perform(post("/api/v1/production-records").principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body(available, unavailable)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_PHOTO_NOT_AVAILABLE"));

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT count(*) FROM production.production_record").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isZero();
    }

    private String upload(String subject) throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "field.png", "image/png", pngBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 现场采集").principal(() -> subject))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    private static String body(String... photoIds) {
        String evidence = photoIds == null || photoIds.length == 0 || photoIds[0] == null ? "[]"
                : "[\"" + String.join("\",\"", photoIds) + "\"]";
        return """
                {"productCode":"CORN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-08","cultivatedAreaMu":"10","yieldPerMuKilograms":"20",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},
                 "submissionMetadata":{"PROD_REPORTER_NAME":"测试填报员","PROD_REPORTER_PHONE":"13800000000",
                  "PROD_SAMPLE_CONTACT":"13900000000","PROD_SAMPLE_LATITUDE":"47.3543",
                  "PROD_SAMPLE_LONGITUDE":"123.9182"},"evidencePhotoIds":%s}
                """.formatted(evidence);
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
}
