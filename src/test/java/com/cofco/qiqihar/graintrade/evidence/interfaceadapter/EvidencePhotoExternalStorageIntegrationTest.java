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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.evidence.content.mode=filesystem")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvidencePhotoExternalStorageIntegrationTest {
    private static final Path CONTENT_ROOT = createContentRoot();

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void contentRoot(DynamicPropertyRegistry registry) {
        registry.add("qiqihar.evidence.content.filesystem-root", CONTENT_ROOT::toString);
    }

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.business_import_draft_evidence,
                  platform.import_job_photo,evidence.evidence_photo
                """).update();
        cleanContentRoot();
    }

    @BeforeTransaction
    void cleanBeforeTestTransaction() throws Exception {
        clean();
    }

    @AfterAll
    static void removeContentRoot() throws Exception {
        cleanContentRoot();
        Files.deleteIfExists(CONTENT_ROOT);
    }

    @Test
    void storesOnlyPrivateLocatorAndReadsVerifiedExternalContent() throws Exception {
        String photoId = upload().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        JdbcClient jdbc = JdbcClient.create(dataSource);

        var row = jdbc.sql("""
                SELECT content_storage_code,content_object_key,
                  original_bytes IS NULL AS original_external,
                  watermarked_bytes IS NULL AS watermarked_external
                FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)
                """).param("id", photoId).query((result, index) -> new Object[] {
                    result.getString("content_storage_code"), result.getString("content_object_key"),
                    result.getBoolean("original_external"), result.getBoolean("watermarked_external")
                }).single();

        assertThat(row[0]).isEqualTo("EXTERNAL");
        assertThat(row[1]).asString().matches(
                "evidence/[0-9a-f]{2}/[0-9a-f-]{36}\\.evp");
        assertThat(row[2]).isEqualTo(true);
        assertThat(row[3]).isEqualTo(true);
        assertThat(Files.isRegularFile(CONTENT_ROOT.resolve(row[1].toString()))).isTrue();

        mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk());
        assertThat(contentReadAuditCount(photoId)).isEqualTo(1);
    }

    @Test
    void authorizesBeforeStoreAccessAndSanitizesStoreFailure() throws Exception {
        String photoId = upload().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        Path displaced = CONTENT_ROOT.resolveSibling(CONTENT_ROOT.getFileName() + "-displaced");
        Files.move(CONTENT_ROOT, displaced);
        Files.writeString(CONTENT_ROOT, "fault");
        try {
            mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                            .principal(() -> "market-tester"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EVIDENCE_PHOTO_ACCESS_DENIED"));
            assertThat(contentReadAuditCount(photoId)).isZero();

            mvc.perform(get("/api/v1/evidence-photos/{id}/content", photoId)
                            .principal(() -> "production-tester"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("EVIDENCE_CONTENT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message")
                            .value("Private evidence content is temporarily unavailable"));
            assertThat(contentReadAuditCount(photoId)).isZero();
        } finally {
            Files.deleteIfExists(CONTENT_ROOT);
            Files.move(displaced, CONTENT_ROOT);
        }
    }

    @Test
    void storeFailureCreatesNeitherMetadataNorFalseSuccess() throws Exception {
        Path displaced = CONTENT_ROOT.resolveSibling(CONTENT_ROOT.getFileName() + "-displaced");
        Files.move(CONTENT_ROOT, displaced);
        Files.writeString(CONTENT_ROOT, "fault");
        try {
            performUpload().andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("EVIDENCE_CONTENT_UNAVAILABLE"));
            assertThat(JdbcClient.create(dataSource).sql("SELECT count(*) FROM evidence.evidence_photo")
                    .query(Long.class).single()).isZero();
        } finally {
            Files.deleteIfExists(CONTENT_ROOT);
            Files.move(displaced, CONTENT_ROOT);
        }
    }

    @Test
    @Transactional
    void outerTransactionRollbackCompensatesTheExactObject() throws Exception {
        String photoId = upload().replaceFirst("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
        String key = JdbcClient.create(dataSource).sql("""
                SELECT content_object_key FROM evidence.evidence_photo WHERE photo_id=CAST(:id AS uuid)
                """).param("id", photoId).query(String.class).single();
        Path object = CONTENT_ROOT.resolve(key);
        assertThat(Files.isRegularFile(object)).isTrue();

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(Files.exists(object)).isFalse();
        assertThat(JdbcClient.create(dataSource).sql("SELECT count(*) FROM evidence.evidence_photo")
                .query(Long.class).single()).isZero();
    }

    private String upload() throws Exception {
        return performUpload().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("STAGED"))
                .andReturn().getResponse().getContentAsString();
    }

    private long contentReadAuditCount(String photoId) {
        return JdbcClient.create(dataSource).sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='EVIDENCE_PHOTO' AND aggregate_id=:id
                  AND action_code='EVIDENCE_PHOTO_CONTENT_READ'
                """).param("id", photoId).query(Long.class).single();
    }

    private org.springframework.test.web.servlet.ResultActions performUpload() throws Exception {
        return mvc.perform(multipart("/api/v1/evidence-photos")
                .file(new MockMultipartFile("file", "field.png", "image/png", pngBytes()))
                .param("capturedAt", "2026-08-08T09:00:00+08:00")
                .param("latitude", "47.3543")
                .param("longitude", "123.9182")
                .param("watermarkText", "齐齐哈尔 现场采集")
                .principal(() -> "production-tester"));
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

    private static Path createContentRoot() {
        try {
            return Files.createTempDirectory("qiqihar-stage7-evidence-");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void cleanContentRoot() throws Exception {
        if (!Files.isDirectory(CONTENT_ROOT)) return;
        try (var paths = Files.walk(CONTENT_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(CONTENT_ROOT)) Files.deleteIfExists(path);
            }
        }
    }
}
