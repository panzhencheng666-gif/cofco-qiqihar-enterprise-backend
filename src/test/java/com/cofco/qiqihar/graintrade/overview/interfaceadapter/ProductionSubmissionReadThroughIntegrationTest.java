package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

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
class ProductionSubmissionReadThroughIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clean() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.business_audit_event,production.production_record,market.market_record,
                  logistics.route_event,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @AfterEach void cleanAfter() { clean(); }

    @Test
    void approvedPhotoBackedSubmissionFlowsFromDatabaseIntoOverviewAndAnalysisMetrics() throws Exception {
        String photoId = uploadPhoto();
        String createResponse = mvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content(body(photoId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.evidencePhotos[0].id").value(photoId))
                .andReturn().getResponse().getContentAsString();
        String recordId = createResponse.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        expectProductionMetrics("0", "0", 0);

        mvc.perform(post("/api/v1/production-records/{id}/submit", recordId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/production-records/{id}/approve", recordId)
                        .principal(() -> "market-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        mvc.perform(get("/api/v1/production-records/{id}", recordId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_REPORTER_NAME").value("产情测试员"))
                .andExpect(jsonPath("$.data.submissionMetadata.PROD_SAMPLE_LATITUDE").value("47.3543"))
                .andExpect(jsonPath("$.data.evidencePhotos[0].state").value("ATTACHED"));
        expectProductionMetrics("10", "200", 1);
    }

    private void expectProductionMetrics(String area, String output, int sourceCount) throws Exception {
        mvc.perform(get("/api/v1/overview/dashboard")
                        .principal(() -> "production-tester")
                        .queryParam("productCode", "CORN")
                        .queryParam("periodCode", "2026-W32")
                        .queryParam("regionCode", "230200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].value").value(area))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_CULTIVATED_AREA')].sourceCount")
                        .value(sourceCount))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_ESTIMATED_OUTPUT')].value").value(output))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'PRODUCTION_ESTIMATED_OUTPUT')].sourceCount")
                        .value(sourceCount));
    }

    private String uploadPhoto() throws Exception {
        String response = mvc.perform(multipart("/api/v1/evidence-photos")
                        .file(new MockMultipartFile("file", "field.png", "image/png", pngBytes()))
                        .param("capturedAt", "2026-08-08T09:00:00+08:00")
                        .param("latitude", "47.3543").param("longitude", "123.9182")
                        .param("watermarkText", "齐齐哈尔 现场采集")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return response.replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    private static String body(String photoId) {
        return """
                {"productCode":"CORN","objectTypeCode":"FARMER","regionCode":"230208",
                 "surveyDate":"2026-08-08","cultivatedAreaMu":"10","yieldPerMuKilograms":"20",
                 "quality":{},"costs":{},"insurance":{},"subsidies":{},
                 "submissionMetadata":{"PROD_REPORTER_NAME":"测试填报员","PROD_REPORTER_PHONE":"13800000000",
                  "PROD_SAMPLE_CONTACT":"13900000000","PROD_SAMPLE_LATITUDE":"47.3543",
                  "PROD_SAMPLE_LONGITUDE":"123.9182"},"evidencePhotoIds":["%s"]}
                """.formatted(photoId);
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
