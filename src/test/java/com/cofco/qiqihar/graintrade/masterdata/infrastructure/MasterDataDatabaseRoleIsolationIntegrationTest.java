package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class MasterDataDatabaseRoleIsolationIntegrationTest {
    private static final String DATASOURCE_ROLE = "cofco_app";
    private static final String RUNTIME_ROLE = "qiqihar_enterprise_runtime";
    private static final String APPLICANT_ROLE = "qiqihar_master_data_applicant";
    private static final String REVIEWER_ROLE = "qiqihar_master_data_reviewer";
    private static final String APPLIER_ROLE = "qiqihar_master_data_applier";
    private static final String APPLICANT_LOGIN = "qiqihar_master_data_applicant_login";
    private static final String REVIEWER_LOGIN = "qiqihar_master_data_reviewer_login";
    private static final String APPLIER_LOGIN = "qiqihar_master_data_applier_login";

    @Autowired DataSource dataSource;

    @Test
    void closesTheV106PrivilegeSurfaceAndExposesOnlyActorFreeResolutionApply() {
        JdbcClient owner = JdbcClient.create(dataSource);
        assertThat(owner.sql("""
                SELECT bool_and(NOT has_table_privilege(:role,relation_name,'INSERT,UPDATE,DELETE'))
                FROM unnest(ARRAY[
                  'registry.sample_subject_resolution_batch',
                  'registry.sample_subject_resolution_item',
                  'registry.sample_subject_resolution_revision',
                  'registry.sample_subject_resolution_audit']) relation_name
                """).param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isTrue();
        assertThat(owner.sql("""
                SELECT NOT has_function_privilege(:role,
                           'registry.apply_sample_subject_resolution(uuid,varchar)','EXECUTE')
                   AND NOT has_function_privilege(:role,
                           'registry.rollback_sample_subject_resolution(uuid,varchar)','EXECUTE')
                """).param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isTrue();
        assertThat(owner.sql("""
                SELECT to_regprocedure('registry.apply_sample_subject_resolution(uuid)') IS NOT NULL
                   AND to_regprocedure('registry.rollback_sample_subject_resolution(uuid)') IS NOT NULL
                """).query(Boolean.class).single()).isTrue();
    }

    @Test
    void functionalRolesHaveDistinctLoginMembersAndRepositoriesUseTheRuntimeEntry() throws Exception {
        JdbcClient owner = JdbcClient.create(dataSource);
        assertThat(owner.sql("""
                SELECT rolname FROM pg_roles
                WHERE rolcanlogin AND rolname IN (:roles)
                ORDER BY rolname
                """).param("roles", List.of(APPLICANT_LOGIN, REVIEWER_LOGIN, APPLIER_LOGIN))
                .query(String.class).list())
                .containsExactlyInAnyOrder(APPLICANT_LOGIN, REVIEWER_LOGIN, APPLIER_LOGIN);
        assertThat(owner.sql("""
                SELECT pg_has_role(:applicant,:applicantRole,'MEMBER')
                   AND pg_has_role(:reviewer,:reviewerRole,'MEMBER')
                   AND pg_has_role(:applier,:applierRole,'MEMBER')
                """).param("applicant", APPLICANT_LOGIN).param("applicantRole", APPLICANT_ROLE)
                .param("reviewer", REVIEWER_LOGIN).param("reviewerRole", REVIEWER_ROLE)
                .param("applier", APPLIER_LOGIN).param("applierRole", APPLIER_ROLE)
                .query(Boolean.class).single()).isTrue();
        assertThat(owner.sql("""
                SELECT has_function_privilege(:role,
                         'platform.register_approved_sample_subject(varchar,varchar,uuid)','EXECUTE')
                """).param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isTrue();
        String runtimeEntry = owner.sql("""
                SELECT pg_get_functiondef(
                  'platform.register_approved_sample_subject(varchar,varchar,uuid)'::regprocedure)
                """).query(String.class).single();
        assertThat(runtimeEntry)
                .contains("session_user::varchar", "current_user::varchar",
                        "database-master-data-automation")
                .doesNotContain("last_modified_by", "business_event_outbox",
                        "point.created_by", "point.updated_by");

        String productionRepository = Files.readString(Path.of(
                "src/main/java/com/cofco/qiqihar/graintrade/production/infrastructure/"
                        + "JdbcProductionRecordRepository.java"));
        assertThat(productionRepository)
                .doesNotContain("govern_master_data_change")
                .contains("register_approved_sample_subject");
        String marketRepository = Files.readString(Path.of(
                "src/main/java/com/cofco/qiqihar/graintrade/market/infrastructure/"
                        + "JdbcMarketMonitoringRepository.java"));
        assertThat(marketRepository)
                .contains("current_sample_subject_resolution")
                .doesNotContain("govern_master_data_change", "register_approved_sample_subject",
                        "sample_point_subject_identity");
    }

    @Test
    void bindsGovernanceActorsToDatabaseRolesAndRuntimeCannotForgeAnyGovernedDomain() {
        JdbcClient owner = JdbcClient.create(dataSource);
        assertThat(owner.sql("SELECT rolname FROM pg_roles WHERE rolname LIKE 'qiqihar_%'")
                .query(String.class).list())
                .contains(RUNTIME_ROLE, APPLICANT_ROLE, REVIEWER_ROLE, APPLIER_ROLE);
        assertThat(owner.sql("SELECT pg_has_role(:role,'qiqihar_enterprise_runtime','USAGE')")
                .param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isTrue();
        assertThat(owner.sql("SELECT pg_has_role(:role,'qiqihar_migration_owner','MEMBER')")
                .param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isFalse();
        assertThat(owner.sql("""
                SELECT bool_and(has_schema_privilege(:role,schema_name,'USAGE'))
                  AND has_table_privilege(:role,'production.production_record','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'market.market_record','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'logistics.route_event','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'supply.calculation_run','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'reporting.report_preview','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'workflow.work_item','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'overview.indicator_definition','SELECT,INSERT,UPDATE,DELETE')
                  AND has_table_privilege(:role,'evidence.evidence_photo','SELECT,INSERT,UPDATE,DELETE')
                FROM unnest(ARRAY['platform','production','market','logistics','supply',
                  'reporting','workflow','overview','evidence','registry']) schema_name
                """).param("role", DATASOURCE_ROLE).query(Boolean.class).single()).isTrue();

        owner.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES('97000000-0000-0000-0000-000000000099','SURVEY_SITE','角色隔离测试点',
                  '230208','APPROVED','MISSING',DATE '2026-01-01','production-tester','production-tester')
                ON CONFLICT(sample_point_id) DO NOTHING
                """).update();

        String originalRegion = snapshot(owner, "REGION", "230208");
        String originalProduct = snapshot(owner, "PRODUCT", "CORN");
        String originalObjectType = snapshot(owner, "OBJECT_TYPE", "FARMER");
        List<Attempt> attempts = List.of(
                new Attempt("REGION", "230208", "UPDATE",
                        withField(owner, originalRegion, "name", "伪造地区"),
                        "UPDATE platform.region SET name='伪造地区' WHERE code='230208'"),
                new Attempt("PRODUCT", "CORN", "UPDATE",
                        withField(owner, originalProduct, "name", "伪造产品"),
                        "UPDATE platform.product SET name='伪造产品' WHERE code='CORN'"),
                new Attempt("OBJECT_TYPE", "FARMER", "UPDATE",
                        withField(owner, originalObjectType, "name", "伪造对象"),
                        "UPDATE platform.object_type SET name='伪造对象' WHERE code='FARMER'"),
                new Attempt("SUBJECT", "PRODUCTION:role-forgery-subject", "INSERT", """
                        {"business_domain":"PRODUCTION","subject_id":"role-forgery-subject",
                         "sample_point_id":"97000000-0000-0000-0000-000000000099",
                         "created_at":"2026-08-12T20:00:00+08:00","created_by":"production-tester"}
                        """, """
                        INSERT INTO registry.sample_point_subject_identity(
                          business_domain,subject_id,sample_point_id,created_at,created_by)
                        VALUES('PRODUCTION','role-forgery-subject',
                          '97000000-0000-0000-0000-000000000099',
                          TIMESTAMPTZ '2026-08-12 20:00:00+08','forged-actor')
                        """));

        for (Attempt attempt : attempts) {
            long requestId = submitAsApplicant(attempt);
            reviewAsReviewer(requestId, "APPROVE", "独立数据库角色复核");
            assertThatThrownBy(() -> executeAsDatasource(requestId, attempt.directDml()))
                    .hasMessageContaining("permission denied");
            assertThat(snapshotIfPresent(owner, attempt.type(), attempt.key()))
                    .doesNotContain("伪造").doesNotContain("forged-actor");
            assertThat(applyAsApplier(requestId)).isTrue();
            assertGovernanceActors(owner, requestId);

            Attempt restore = switch (attempt.type()) {
                case "REGION" -> new Attempt("REGION", attempt.key(), "UPDATE", originalRegion, "");
                case "PRODUCT" -> new Attempt("PRODUCT", attempt.key(), "UPDATE", originalProduct, "");
                case "OBJECT_TYPE" ->
                        new Attempt("OBJECT_TYPE", attempt.key(), "UPDATE", originalObjectType, "");
                case "SUBJECT" -> new Attempt("SUBJECT", attempt.key(), "DELETE",
                        snapshotIfPresent(owner, attempt.type(), attempt.key()), "");
                default -> throw new IllegalArgumentException("Unsupported governed domain");
            };
            long restoreRequest = submitAsApplicant(restore);
            reviewAsReviewer(restoreRequest, "APPROVE", "恢复角色隔离测试前主数据快照");
            assertThat(applyAsApplier(restoreRequest)).isTrue();
        }

        long productRequest = owner.sql("""
                SELECT request_id FROM platform.master_data_change_request
                WHERE entity_type='PRODUCT' AND entity_key='CORN'
                  AND target_snapshot->>'name'='伪造产品'
                ORDER BY request_id DESC LIMIT 1
                """).query(Long.class).single();
        assertGovernanceActors(owner, productRequest);
        assertThat(owner.sql("""
                SELECT changed_by FROM platform.master_data_revision
                WHERE change_request_id=:requestId
                """).param("requestId", productRequest).query(String.class).single())
                .isEqualTo(APPLIER_LOGIN);

        assertThatThrownBy(this::forgeRequestAsDatasource).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> forgeEventAsDatasource(productRequest)).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> submitAsDatasource(attempts.get(0))).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> governAsDatasource(attempts.get(0))).hasMessageContaining("permission denied");
    }

    private void assertGovernanceActors(JdbcClient owner, long requestId) {
        assertThat(owner.sql("""
                SELECT r.requested_by,
                       max(e.actor) FILTER (WHERE e.event_type='APPROVED') AS reviewed_by,
                       max(e.actor) FILTER (WHERE e.event_type='APPLIED') AS applied_by
                FROM platform.master_data_change_request r
                JOIN platform.master_data_change_event e USING(request_id)
                WHERE r.request_id=:requestId GROUP BY r.requested_by
                """).param("requestId", requestId).query().singleRow())
                .containsEntry("requested_by", APPLICANT_LOGIN)
                .containsEntry("reviewed_by", REVIEWER_LOGIN)
                .containsEntry("applied_by", APPLIER_LOGIN);
    }

    private long submitAsApplicant(Attempt attempt) {
        return asRole(APPLICANT_LOGIN, APPLICANT_ROLE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT platform.submit_master_data_change(?,?,?,CAST(? AS jsonb),?,?)
                    """)) {
                statement.setString(1, attempt.type());
                statement.setString(2, attempt.key());
                statement.setString(3, attempt.operation());
                statement.setString(4, attempt.snapshot());
                statement.setObject(5, OffsetDateTime.now().minusSeconds(1));
                statement.setString(6, "数据库身份绑定申请");
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
    }

    private void reviewAsReviewer(long requestId, String decision, String basis) {
        asRole(REVIEWER_LOGIN, REVIEWER_ROLE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT platform.review_master_data_change(?,?,?)")) {
                statement.setLong(1, requestId);
                statement.setString(2, decision);
                statement.setString(3, basis);
                statement.executeQuery();
                return null;
            }
        });
    }

    private boolean applyAsApplier(long requestId) {
        return asRole(APPLIER_LOGIN, APPLIER_ROLE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT platform.apply_master_data_change(?)")) {
                statement.setLong(1, requestId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getBoolean(1);
                }
            }
        });
    }

    private void executeAsDatasource(long requestId, String directDml) {
        asRole(DATASOURCE_ROLE, null, connection -> {
            try (PreparedStatement context = connection.prepareStatement("""
                    SELECT set_config('application.master_data_apply_request_id',?,false),
                           set_config('application.actor','forged-runtime-actor',false)
                    """)) {
                context.setString(1, Long.toString(requestId));
                context.executeQuery();
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(directDml);
            }
            return null;
        });
    }

    private void forgeRequestAsDatasource() {
        asRole(DATASOURCE_ROLE, null, connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO platform.master_data_change_request(
                          entity_type,entity_key,operation_code,target_relation,target_snapshot,
                          requested_by,request_basis,effective_at)
                        VALUES('PRODUCT','CORN','UPDATE','platform.product',
                          '{"code":"CORN","name":"伪造"}'::jsonb,'forged','forged',clock_timestamp())
                        """);
            }
            return null;
        });
    }

    private void forgeEventAsDatasource(long requestId) {
        asRole(DATASOURCE_ROLE, null, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO platform.master_data_change_event(request_id,event_type,actor,basis)
                    VALUES(?,'APPLIED','forged','forged')
                    """)) {
                statement.setLong(1, requestId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void submitAsDatasource(Attempt attempt) {
        asRole(DATASOURCE_ROLE, null, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT platform.submit_master_data_change(?,?,?,CAST(? AS jsonb),?,?)
                    """)) {
                statement.setString(1, attempt.type());
                statement.setString(2, attempt.key());
                statement.setString(3, attempt.operation());
                statement.setString(4, attempt.snapshot());
                statement.setObject(5, OffsetDateTime.now());
                statement.setString(6, "forged");
                statement.executeQuery();
            }
            return null;
        });
    }

    private void governAsDatasource(Attempt attempt) {
        asRole(DATASOURCE_ROLE, null, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT platform.govern_master_data_change(
                      ?,?,?,CAST(? AS jsonb),clock_timestamp(),'forged-applicant',
                      'forged-reviewer','forged')
                    """)) {
                statement.setString(1, attempt.type());
                statement.setString(2, attempt.key());
                statement.setString(3, attempt.operation());
                statement.setString(4, attempt.snapshot());
                statement.executeQuery();
            }
            return null;
        });
    }

    private <T> T asRole(String loginRole, String functionalRole, RoleWork<T> work) {
        if (!List.of(DATASOURCE_ROLE, APPLICANT_LOGIN, REVIEWER_LOGIN, APPLIER_LOGIN)
                .contains(loginRole)) {
            throw new IllegalArgumentException("Unsupported test database role");
        }
        try (Connection connection = dataSource.getConnection();
                Statement authorization = connection.createStatement()) {
            authorization.execute("SET SESSION AUTHORIZATION " + loginRole);
            if (functionalRole != null) {
                authorization.execute("SET ROLE " + functionalRole);
            }
            try {
                return work.run(connection);
            } finally {
                if (functionalRole != null) {
                    authorization.execute("RESET ROLE");
                }
                authorization.execute("RESET SESSION AUTHORIZATION");
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private String snapshot(JdbcClient owner, String type, String key) {
        return switch (type) {
            case "REGION" -> owner.sql("SELECT to_jsonb(r)::text FROM platform.region r WHERE code=:key")
                    .param("key", key).query(String.class).single();
            case "PRODUCT" -> owner.sql("SELECT to_jsonb(p)::text FROM platform.product p WHERE code=:key")
                    .param("key", key).query(String.class).single();
            case "OBJECT_TYPE" -> owner.sql("SELECT to_jsonb(o)::text FROM platform.object_type o WHERE code=:key")
                    .param("key", key).query(String.class).single();
            default -> throw new IllegalArgumentException(type);
        };
    }

    private String snapshotIfPresent(JdbcClient owner, String type, String key) {
        if (type.equals("SUBJECT")) {
            return owner.sql("""
                    SELECT COALESCE((SELECT to_jsonb(s)::text
                      FROM registry.sample_point_subject_identity s
                      WHERE business_domain=split_part(:key,':',1)
                        AND subject_id=substring(:key from position(':' in :key)+1)), '{}')
                    """).param("key", key).query(String.class).single();
        }
        return snapshot(owner, type, key);
    }

    private String withField(JdbcClient owner, String snapshot, String field, String value) {
        return owner.sql("SELECT (CAST(:snapshot AS jsonb) || jsonb_build_object(:field,:value))::text")
                .param("snapshot", snapshot).param("field", field).param("value", value)
                .query(String.class).single();
    }

    private record Attempt(String type, String key, String operation, String snapshot, String directDml) {}

    @FunctionalInterface
    private interface RoleWork<T> {
        T run(Connection connection) throws Exception;
    }
}
