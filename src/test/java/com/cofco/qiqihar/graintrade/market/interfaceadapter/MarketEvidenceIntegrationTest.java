package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.junit.jupiter.api.BeforeEach;
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
class MarketEvidenceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clean() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.business_audit_event,market.market_record,
                  evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void requiresAtLeastOnePrivatePhotoAndReturnsOnlyItsMetadata() throws Exception {
        mvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_EVIDENCE_PHOTO"));
        assertThat(recordCount()).isZero();

        String photoId = upload("market-tester");
        String response = mvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body(photoId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.evidencePhotos[0].id").value(photoId))
                .andExpect(jsonPath("$.data.evidencePhotos[0].watermarkText").value("齐齐哈尔 市场采集"))
                .andExpect(jsonPath("$.data.evidencePhotos[0].bytes").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String recordId = response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/market-records/{id}", recordId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidencePhotos[0].id").value(photoId));
        assertThat(JdbcClient.create(dataSource).sql("""
                        SELECT attached_domain || ':' || attached_record_id
                        FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)
                        """).param("id", photoId).query(String.class).single())
                .isEqualTo("MARKET:" + recordId);
    }

    @Test
    void mixedOwnedAndForeignPhotosRollBackRecordAndEveryAttachment() throws Exception {
        String owned = upload("market-tester");
        String foreign = upload("production-tester");

        mvc.perform(post("/api/v1/market-records").principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body(owned, foreign)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_PHOTO_NOT_AVAILABLE"));

        assertThat(recordCount()).isZero();
        assertThat(JdbcClient.create(dataSource).sql(
                        "SELECT count(*) FROM evidence.evidence_photo WHERE state_code='ATTACHED'")
                .query(Long.class).single()).isZero();
    }

    private String upload(String subject) throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "market.png", "image/png", pngBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 市场采集").principal(() -> subject))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    private long recordCount() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM market.market_record")
                .query(Long.class).single();
    }

    private static String body(String... photoIds) {
        String evidence = photoIds == null || photoIds.length == 0 ? "[]"
                : "[\"" + String.join("\",\"", photoIds) + "\"]";
        return """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"FEED_MILL","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_REPORTER_NAME":"测试填报员","MKT_SURVEYOR_NAME":"王雷",
                 "MKT_SURVEYOR_PHONE":"13800000000",
                 "MKT_SAMPLE_NAME":"齐齐哈尔第一粮店","MKT_SAMPLE_CONTACT":"13900000000",
                 "MKT_SAMPLE_LATITUDE":"47.3543","MKT_SAMPLE_LONGITUDE":"123.9182"},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},"evidencePhotoIds":%s}
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
