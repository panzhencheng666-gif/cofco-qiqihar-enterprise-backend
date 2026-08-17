package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class DataLifecycleGovernanceIntegrationTest {
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        jdbc.sql("""
                TRUNCATE platform.business_import_draft_evidence,
                  platform.import_job_photo,evidence.evidence_photo
                """).update();
    }

    @Test
    void publishesOwnedRetentionPoliciesAndStableNonProductionMasking() {
        assertThat(jdbc.sql("""
                SELECT data_class FROM platform.data_lifecycle_policy
                WHERE governance_state='ENFORCED' ORDER BY data_class
                """).query(String.class).list())
                .contains("EVIDENCE_PHOTO", "SECURITY_IDENTITY", "STABLE_SUBJECT_IDENTITY");

        var masked = jdbc.sql("""
                SELECT masked_subject_id,masked_display_name,work_unit_code
                FROM platform.security_user_nonproduction_masked
                WHERE source_fingerprint=encode(sha256(convert_to('production-tester','UTF8')),'hex')
                """).query().singleRow();
        assertThat(masked.get("masked_subject_id")).asString().matches("employee-[0-9a-f]{12}");
        assertThat(masked.get("masked_display_name")).asString().matches("测试员工-[0-9a-f]{8}");
        assertThat(masked.values()).noneMatch(value -> "production-tester".equals(value));
    }

    @Test
    void verifiesBothPhotoDigestsAndRejectsTampering() throws Exception {
        UUID photo = insertPhoto("digest", OffsetDateTime.parse("2026-08-01T09:00:00+08:00"));
        var consistency = jdbc.sql("""
                SELECT original_length_matches,original_digest_matches,watermarked_digest_matches,
                       attachment_reference_matches,consistency_state
                FROM evidence.evidence_photo_consistency WHERE photo_id=:id
                """).param("id", photo).query().singleRow();
        assertThat(consistency.get("original_length_matches")).isEqualTo(true);
        assertThat(consistency.get("original_digest_matches")).isEqualTo(true);
        assertThat(consistency.get("watermarked_digest_matches")).isEqualTo(true);
        assertThat(consistency.get("attachment_reference_matches")).isEqualTo(true);
        assertThat(consistency.get("consistency_state")).isEqualTo("CONSISTENT");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE evidence.evidence_photo SET original_bytes=:tampered WHERE photo_id=:id
                """).param("tampered", new byte[] {9, 9, 9}).param("id", photo).update())
                .hasMessageContaining("evidence_photo_content_digest_check");
    }

    @Test
    void classifiesMissingAndWrongRegionAttachmentReferencesAsInconsistent() throws Exception {
        UUID photo = insertPhoto("orphan", OffsetDateTime.parse("2026-08-01T09:00:00+08:00"));
        jdbc.sql("""
                UPDATE evidence.evidence_photo SET state_code='ATTACHED',attached_domain='PRODUCTION',
                  attached_record_id='missing-record',attached_region_code='230208' WHERE photo_id=:id
                """).param("id", photo).update();
        assertAttachmentInconsistent(photo);

        jdbc.sql("""
                INSERT INTO production.production_record(
                  record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                  cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES('97000000-0000-0000-0000-000000000108','CORN','FARMER','230208',
                  DATE '2026-08-01',now(),1,1,'APPROVED','production-tester')
                """).update();
        jdbc.sql("""
                UPDATE evidence.evidence_photo SET attached_record_id='97000000-0000-0000-0000-000000000108',
                  attached_region_code='230200' WHERE photo_id=:id
                """).param("id", photo).update();
        assertAttachmentInconsistent(photo);
    }

    @Test
    void legalHoldBlocksDestructionAndRetentionCandidatesExcludeHeldPhotos() throws Exception {
        UUID photo = insertPhoto("hold", OffsetDateTime.parse("2020-01-01T09:00:00+08:00"));
        jdbc.sql("""
                INSERT INTO platform.data_legal_hold(
                  hold_id,resource_type,resource_id,reason,placed_by,placed_at)
                VALUES(gen_random_uuid(),'EVIDENCE_PHOTO',:id,'诉讼保全','production-tester',now())
                """).param("id", photo.toString()).update();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM evidence.evidence_photo_retention_candidate WHERE photo_id=:id
                """).param("id", photo).query(Long.class).single()).isZero();
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM evidence.evidence_photo WHERE photo_id=:id")
                .param("id", photo).update()).hasMessageContaining("active legal hold");
        assertThatThrownBy(() -> jdbc.sql("""
                DELETE FROM platform.data_legal_hold
                WHERE resource_type='EVIDENCE_PHOTO' AND resource_id=:id
                """).param("id", photo.toString()).update())
                .hasMessageContaining("legal holds may only be released");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.data_legal_hold_event event
                JOIN platform.data_legal_hold hold ON hold.hold_id=event.hold_id
                WHERE hold.resource_type='EVIDENCE_PHOTO' AND hold.resource_id=:id
                  AND event.action_code='PLACED'
                """).param("id", photo.toString()).query(Long.class).single()).isOne();

        jdbc.sql("""
                UPDATE platform.data_legal_hold SET released_at=now(),released_by='production-tester'
                WHERE resource_type='EVIDENCE_PHOTO' AND resource_id=:id
                """).param("id", photo.toString()).update();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.data_legal_hold_event event
                JOIN platform.data_legal_hold hold ON hold.hold_id=event.hold_id
                WHERE hold.resource_type='EVIDENCE_PHOTO' AND hold.resource_id=:id
                  AND event.action_code='RELEASED'
                """).param("id", photo.toString()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("DELETE FROM evidence.evidence_photo WHERE photo_id=:id")
                .param("id", photo).update()).isEqualTo(1);
    }

    private UUID insertPhoto(String suffix, OffsetDateTime uploadedAt) throws Exception {
        UUID id = UUID.randomUUID();
        byte[] original = ("original-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] watermarked = ("watermarked-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(
                  photo_id,state_code,original_filename,media_type,original_bytes,watermarked_bytes,
                  byte_length,sha256,captured_at,capture_latitude,capture_longitude,
                  watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED',:filename,'image/png',:original,:watermarked,:length,:sha,
                  :uploadedAt,47.3543,123.9182,'治理核对','production-tester',:uploadedAt)
                """).param("id", id).param("filename", suffix + ".png")
                .param("original", original).param("watermarked", watermarked)
                .param("length", original.length).param("sha", sha256(original))
                .param("uploadedAt", uploadedAt).update();
        return id;
    }

    private void assertAttachmentInconsistent(UUID photo) {
        var row = jdbc.sql("""
                SELECT attachment_reference_matches,consistency_state
                FROM evidence.evidence_photo_consistency WHERE photo_id=:id
                """).param("id", photo).query().singleRow();
        assertThat(row.get("attachment_reference_matches")).isEqualTo(false);
        assertThat(row.get("consistency_state")).isEqualTo("INCONSISTENT");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
