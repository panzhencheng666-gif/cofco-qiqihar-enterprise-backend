package com.cofco.qiqihar.graintrade.production.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabase;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProductionInventoryContractMigrationTest {
    private static final ProtectedTestDatabase DATABASE = ProtectedTestDatabase.shared();
    private static final String[] BUSINESS_SCHEMAS = {
        "platform", "production", "market", "logistics", "supply", "reporting", "workflow", "overview",
        "evidence", "registry"
    };

    @AfterEach
    void restoreLatestSchema() {
        DATABASE.flyway().migrate();
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM reporting.approved_dataset WHERE dataset_id='11800000-0000-0000-0000-000000000001'");
            statement.execute("DELETE FROM production.production_record WHERE record_id='inventory-upgrade-fixture'");
            ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(
                    JdbcClient.create(DATABASE.dataSource()));
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to clean production inventory migration fixture", failure);
        }
    }

    @Test
    void freshMigrationVersionsPublicProductionInventoryWithoutRemovingBusinessFields() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(166);

        assertThat(query("""
                SELECT EXISTS(
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema='overview'
                    AND table_name='region_surplus_calculation_contract'
                    AND column_name='status_code')
                """)).isEqualTo("t");
        assertThat(query("""
                SELECT string_agg(version_code || ':' || status_code || ':' ||
                  CASE WHEN effective_from IS NULL THEN 'PENDING' ELSE 'SET' END || ':' ||
                  CASE WHEN effective_to IS NULL THEN 'OPEN' ELSE 'CLOSED' END,
                  ',' ORDER BY version_code)
                FROM overview.region_surplus_calculation_contract
                """)).isEqualTo(
                        "REGION_SURPLUS_V1:ACTIVE:SET:OPEN,"
                                + "REGION_SURPLUS_V2:PENDING:PENDING:OPEN");
        assertThat(query("""
                SELECT name FROM overview.region_surplus_calculation_contract
                WHERE status_code='ACTIVE' AND TIMESTAMPTZ '2026-08-12 23:59:59+08' >= effective_from
                  AND (effective_to IS NULL OR TIMESTAMPTZ '2026-08-12 23:59:59+08' < effective_to)
                """)).isEqualTo("地区余粮公开填报口径第1版");
        assertThat(query("""
                SELECT string_agg(code,',' ORDER BY code) FROM platform.production_fact_definition
                WHERE code IN ('PROD_OPENING_INVENTORY','PROD_ENDING_INVENTORY')
                """)).isEqualTo("PROD_ENDING_INVENTORY,PROD_OPENING_INVENTORY");
        assertThat(query("""
                SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                  array_to_string(proconfig,',')
                FROM pg_proc
                WHERE oid='registry.apply_sample_subject_resolution(uuid,varchar)'::regprocedure
                """)).isEqualTo(
                        "qiqihar_migration_owner:false:search_path=pg_catalog, registry, production, market");
        assertThat(query("""
                SELECT has_function_privilege('cofco_app',
                         'registry.apply_sample_subject_resolution(uuid,varchar)','EXECUTE') || ':' ||
                       has_function_privilege('qiqihar_master_data_applier',
                         'registry.apply_sample_subject_resolution(uuid)','EXECUTE') || ':' ||
                       prosecdef
                FROM pg_proc
                WHERE oid='registry.apply_sample_subject_resolution(uuid)'::regprocedure
                """)).isEqualTo("false:true:true");
        assertThat(query("""
                SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                  array_to_string(proconfig,',')
                FROM pg_proc
                WHERE oid='registry.correct_approved_market_inventory_resolution(uuid,varchar)'::regprocedure
                """)).isEqualTo(
                        "qiqihar_migration_owner:false:search_path=pg_catalog, registry, production, market");
        assertThat(query("""
                SELECT has_function_privilege('cofco_app',
                         'registry.correct_approved_market_inventory_resolution(uuid,varchar)','EXECUTE') || ':' ||
                       has_function_privilege('cofco_app',
                         'registry.correct_approved_market_inventory_resolution(uuid)','EXECUTE') || ':' ||
                       has_function_privilege('qiqihar_master_data_applier',
                         'registry.correct_approved_market_inventory_resolution(uuid)','EXECUTE') || ':' ||
                       prosecdef
                FROM pg_proc
                WHERE oid='registry.correct_approved_market_inventory_resolution(uuid)'::regprocedure
                """)).isEqualTo("false:false:true:true");
        assertThat(query("""
                SELECT COALESCE((
                  SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                    array_to_string(proconfig,',') || ':' ||
                    has_function_privilege('cofco_app',oid,'EXECUTE') || ':' ||
                    has_function_privilege('qiqihar_master_data_applier',oid,'EXECUTE')
                  FROM pg_proc
                  WHERE oid=to_regprocedure(
                    'registry.lock_sample_subject_identity_keys(varchar,varchar[],uuid[])')
                ),'MISSING')
                """)).isEqualTo(
                        "qiqihar_migration_owner:true:search_path=pg_catalog, registry:false:false");
        assertThat(query("""
                SELECT COALESCE((
                  SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                    array_to_string(proconfig,',') || ':' ||
                    has_function_privilege('cofco_app',oid,'EXECUTE') || ':' ||
                    has_function_privilege('qiqihar_master_data_applier',oid,'EXECUTE')
                  FROM pg_proc
                  WHERE oid=to_regprocedure(
                    'registry.guard_sample_point_subject_identity_consistency()')
                ),'MISSING')
                """)).isEqualTo(
                        "qiqihar_migration_owner:true:search_path=pg_catalog, registry:false:false");
        assertThat(query("""
                SELECT COALESCE((
                  SELECT pg_get_triggerdef(oid)
                  FROM pg_trigger
                  WHERE tgrelid='registry.sample_point_subject_identity'::regclass
                    AND tgname='subject_identity_cross_projection_gate'
                    AND NOT tgisinternal
                ),'MISSING')
                """)).contains(
                        "BEFORE INSERT OR DELETE OR UPDATE",
                        "registry.guard_sample_point_subject_identity_consistency()");
        assertThat(query("""
                SELECT pg_get_userbyid(proowner) || ':' || prosecdef || ':' ||
                  array_to_string(proconfig,',') || ':' ||
                  has_function_privilege('cofco_app',oid,'EXECUTE') || ':' ||
                  EXISTS(SELECT 1
                    FROM aclexplode(coalesce(proacl,acldefault('f',proowner))) privilege
                    WHERE privilege.grantee=0 AND privilege.privilege_type='EXECUTE')
                FROM pg_proc
                WHERE oid='platform.register_approved_sample_subject(varchar,varchar,uuid)'::regprocedure
                """)).isEqualTo(
                        "qiqihar_migration_owner:true:"
                                + "search_path=pg_catalog, platform, registry, production, market:true:false");
        assertRuntimeRoleCannotExecuteApprovedInventoryCorrection();
    }

    @Test
    void runtimeRoleCanReadOverviewRegionSurplusContract() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();

        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE cofco_app");
                try (ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM overview.region_surplus_calculation_contract
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void freshMigrationGrantsRuntimeOverviewContractReadOnlyAccess() throws Exception {
        resetDatabase();
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isEqualTo(166);

        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'overview.region_surplus_calculation_contract','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'overview.region_surplus_calculation_contract','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'overview.region_surplus_calculation_contract','UPDATE') || ':' ||
                       has_table_privilege('cofco_app',
                         'overview.region_surplus_calculation_contract','DELETE')
                """)).isEqualTo("true:false:false:false");
        assertThat(query("""
                SELECT has_column_privilege('cofco_app',
                         'market.market_inventory_governance','record_id','INSERT') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','status_code','INSERT,UPDATE') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','reason_code','INSERT,UPDATE') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','sample_point_id','INSERT,UPDATE') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','resolved_by','INSERT,UPDATE') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','resolved_at','INSERT,UPDATE') || ':' ||
                       has_column_privilege('cofco_app',
                         'market.market_inventory_governance','record_id','UPDATE')
                """)).isEqualTo("true:true:true:true:true:true:false");
        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'market.market_inventory_governance','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.market_inventory_governance','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.market_inventory_governance','UPDATE') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.market_inventory_governance','DELETE')
                """)).isEqualTo("true:false:false:true");
        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','UPDATE') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','DELETE')
                """)).isEqualTo("true:false:false:false");
        assertRuntimeCanReadOverviewRegionSurplusContract();
        assertRuntimeCanReadMarketInventoryGovernance();
        assertRuntimeCanReadMarketInventoryContract();
        assertRuntimeCannotMutateOverviewRegionSurplusContract();
        assertRuntimeCanManageMarketInventoryGovernance();
        assertRuntimeCannotMutateMarketInventoryContract();
        assertThat(query("""
                SELECT version || ':' || checksum || ':' || success
                FROM public.flyway_schema_history
                WHERE version IN ('118','119')
                ORDER BY installed_rank
                """)).contains("118:");
    }

    @Test
    void upgradesV118WithOnlyV119RuntimeOverviewContractGrant() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("118").migrate();
        assertThat(query("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"))
                .isEqualTo("118");

        assertThat(DATABASE.flywayToVersion("119").migrate().migrationsExecuted).isOne();
        assertThat(query("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"))
                .isEqualTo("119");
        assertRuntimeCanReadOverviewRegionSurplusContract();
        assertRuntimeCanReadMarketInventoryGovernance();
        assertRuntimeCannotMutateOverviewRegionSurplusContract();
        assertRuntimeCannotMutateMarketInventoryGovernance();
    }

    @Test
    void upgradesV121WithRuntimeMarketInventoryContractReadOnlyAccess() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("121").migrate();
        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','SELECT')
                """)).isEqualTo("f");

        assertThat(DATABASE.flywayToVersion("122").migrate().migrationsExecuted).isOne();

        assertThat(query("""
                SELECT has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','SELECT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','INSERT') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','UPDATE') || ':' ||
                       has_table_privilege('cofco_app',
                         'market.sample_point_inventory_contract','DELETE')
                """)).isEqualTo("true:false:false:false");
        assertRuntimeCanReadMarketInventoryContract();
        assertRuntimeCannotMutateMarketInventoryContract();
    }

    @Test
    void upgradesV122WithScopedRuntimeMarketInventoryGovernanceLifecycle() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("122").migrate();
        assertRuntimeCannotMutateMarketInventoryGovernance();

        assertThat(DATABASE.flywayToVersion("123").migrate().migrationsExecuted).isOne();

        assertRuntimeCanManageMarketInventoryGovernance();
        assertRuntimeCannotMutateMarketInventoryContract();
    }

    @Test
    void upgradesV117WithoutChangingLogisticsContractOrHistoricalProductionValues() throws Exception {
        resetDatabase();
        DATABASE.flywayToVersion("117").migrate();
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                      survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                    VALUES('inventory-upgrade-fixture','CORN','FARMER','230202',DATE '2026-08-01',
                      CURRENT_TIMESTAMP,10,2,'APPROVED','migration-test')
                    """);
            statement.execute("""
                    INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
                    VALUES('inventory-upgrade-fixture','PROD_OPENING_INVENTORY','12'),
                          ('inventory-upgrade-fixture','PROD_ENDING_INVENTORY','8'),
                          ('inventory-upgrade-fixture','PROD_SURPLUS_SUBJECT_CODE','legacy-2026-farmer'),
                          ('inventory-upgrade-fixture','PROD_SURPLUS_CUTOFF_DATE','2026-08-31')
                    """);
            statement.execute("""
                    INSERT INTO reporting.approved_dataset(
                      dataset_id,report_definition_id,product_code,region_level,region_code,period_code,
                      frequency_code,source_state,source_summary,immutable_digest,captured_at,captured_by)
                    SELECT '11800000-0000-0000-0000-000000000001',report_definition_id,'CORN',
                      'PREFECTURE','230200','2026-Q3','DAILY','APPROVED',
                      '{"regionSurplus":"8","calculationVersion":"地区余粮口径第1版"}'::jsonb,
                      'pre-v118-immutable-digest',TIMESTAMPTZ '2026-08-12 16:00:00+08','migration-test'
                    FROM reporting.report_definition WHERE code='PRODUCTION_DAILY'
                    """);
        }

        assertThat(DATABASE.flywayToVersion("119").migrate().migrationsExecuted).isEqualTo(2);
        assertThat(query("""
                SELECT string_agg(field_code || ':' || value,',' ORDER BY field_code)
                FROM production.production_record_submission_metadata
                WHERE record_id='inventory-upgrade-fixture'
                """)).isEqualTo("PROD_ENDING_INVENTORY:8,PROD_OPENING_INVENTORY:12,"
                        + "PROD_SURPLUS_CUTOFF_DATE:2026-08-31,PROD_SURPLUS_SUBJECT_CODE:legacy-2026-farmer");
        assertThat(query("""
                SELECT source_summary::text || ':' || immutable_digest
                FROM reporting.approved_dataset
                WHERE dataset_id='11800000-0000-0000-0000-000000000001'
                """)).isEqualTo("{\"regionSurplus\": \"8\", \"calculationVersion\": \"地区余粮口径第1版\"}:"
                        + "pre-v118-immutable-digest");
        assertThat(query("""
                SELECT name FROM overview.region_surplus_calculation_contract
                WHERE status_code='ACTIVE' AND CURRENT_TIMESTAMP>=effective_from
                  AND (effective_to IS NULL OR CURRENT_TIMESTAMP<effective_to)
                """)).isEqualTo("地区余粮口径第1版");
        assertThat(query("""
                SELECT count(*) FROM platform.logistics_core_field_applicability
                WHERE product_code='CORN'
                """)).isEqualTo("16");
        assertThat(query("""
                SELECT pg_get_functiondef('supply.validate_release_period_provenance()'::regprocedure)
                """))
                .contains("record.survey_year=period.survey_year")
                .contains("record.survey_month IS NOT NULL")
                .doesNotContain("EXTRACT(YEAR FROM record.survey_date)");
    }

    @Test
    void rejectsLegacyAndCurrentCrossProjectionConflictsAtomicallyOnUpgrade() throws Exception {
        for (CrossProjectionPollution pollution : CrossProjectionPollution.values()) {
            resetDatabase();
            DATABASE.flywayToVersion("117").migrate();
            installCrossProjectionPollution(pollution);
            try {
                Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                        () -> DATABASE.flyway().migrate());

                assertThat(failure).isNotNull();
                assertThat(rootMessage(failure)).contains(pollution.expectedFailure());
                assertThat(query("""
                        SELECT version FROM public.flyway_schema_history
                        WHERE success ORDER BY installed_rank DESC LIMIT 1
                        """)).isEqualTo("117");
                assertThat(query("""
                        SELECT to_regclass('overview.region_surplus_calculation_contract') IS NULL
                          AND NOT EXISTS(
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema='registry'
                              AND table_name='sample_subject_resolution_item'
                              AND column_name='expected_predecessor_resolution_revision_id')
                        """)).isEqualTo("t");
            } finally {
                removeCrossProjectionPollution();
            }
        }
    }

    @Test
    void rejectsOverlappingOrGappedActivatedCalculationContracts() throws Exception {
        resetDatabase();
        DATABASE.flyway().migrate();

        assertThatThrownBySql("""
                INSERT INTO overview.region_surplus_calculation_contract(
                  version_code,name,status_code,effective_from,effective_to,
                  production_identity_source,production_cutoff_source,formula,
                  activated_by,activation_basis,activated_at)
                VALUES('OVERLAP','重叠口径','RETIRED',TIMESTAMPTZ '2026-01-01 00:00:00+08',
                  TIMESTAMPTZ '2026-02-01 00:00:00+08','x','x','x','test','重叠负例',now())
                """);
        assertThatRejectedAtDeferredConstraintCheck("""
                UPDATE overview.region_surplus_calculation_contract
                SET effective_from=clock_timestamp()+interval '1 day'
                WHERE version_code='REGION_SURPLUS_V1'
                """);
        execute("""
                SELECT overview.activate_region_surplus_calculation_contract(
                  'REGION_SURPLUS_V2',clock_timestamp()-interval '1 hour','migration-test','批准后的显式生效边界')
                """);
        assertThatThrownBySql("""
                UPDATE overview.region_surplus_calculation_contract
                SET effective_to=effective_to-interval '1 minute'
                WHERE version_code='REGION_SURPLUS_V1'
                """);
        assertThat(query("""
                SELECT count(*) FROM overview.region_surplus_calculation_activation_audit
                WHERE version_code='REGION_SURPLUS_V2' AND activated_by='migration-test'
                  AND activation_basis='批准后的显式生效边界'
                """)).isEqualTo("1");
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            for (String schema : BUSINESS_SCHEMAS) statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private void installCrossProjectionPollution(CrossProjectionPollution pollution)
            throws Exception {
        String legacyStable = "migration-legacy-stable";
        String currentStable = pollution == CrossProjectionPollution.SAME_STABLE_DIFFERENT_POINT
                ? legacyStable : "migration-current-stable";
        String currentPoint = pollution == CrossProjectionPollution.SAME_STABLE_DIFFERENT_POINT
                ? "11800000-0000-0000-0000-000000000102"
                : "11800000-0000-0000-0000-000000000101";
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("""
                        INSERT INTO registry.sample_point(
                          sample_point_id,kind_code,canonical_name,region_code,approval_state,
                          location_state,effective_from,created_by,updated_by)
                        VALUES
                          ('11800000-0000-0000-0000-000000000101','SURVEY_SITE',
                            'V118污染升级样本点甲','230200','APPROVED','MISSING',DATE '2026-01-01',
                            'database-master-data-automation','database-master-data-automation'),
                          ('11800000-0000-0000-0000-000000000102','SURVEY_SITE',
                            'V118污染升级样本点乙','230200','APPROVED','MISSING',DATE '2026-01-01',
                            'database-master-data-automation','database-master-data-automation')
                        """);
                statement.execute("SET LOCAL session_replication_role=replica");
                statement.execute("""
                        INSERT INTO registry.sample_point_subject_identity(
                          business_domain,subject_id,sample_point_id,created_at,created_by)
                        VALUES('MARKET','migration-legacy-stable',
                          '11800000-0000-0000-0000-000000000101',now(),
                          'database-master-data-automation')
                        """);
                statement.execute("SET LOCAL session_replication_role=origin");
                statement.execute("""
                        INSERT INTO registry.sample_subject_resolution_batch(
                          batch_id,idempotency_key,input_digest,expected_item_count,status_code,
                          created_at,created_by,applied_at,applied_by)
                        VALUES('11800000-0000-0000-0000-000000000110',
                          'v118-cross-projection-pollution',repeat('a',64),1,'APPLIED',now(),
                          'migration-test',now(),'migration-test')
                        """);
                statement.execute("""
                        INSERT INTO registry.sample_subject_resolution_item(
                          batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                          resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code,
                          before_snapshot,after_snapshot,before_sha256,after_sha256,
                          applied_source_version,applied_resolution_revision_id,applied_at,applied_by)
                        VALUES('11800000-0000-0000-0000-000000000110',1,'MARKET',
                          'v118-polluted-current',0,'LINK','%s','%s',
                          'EXT_007_EXPLICIT_DISPOSITION','APPLIED','{}','{}',repeat('b',64),
                          repeat('c',64),0,'11800000-0000-0000-0000-000000000111',now(),
                          'migration-test')
                        """.formatted(currentStable, currentPoint));
                statement.execute("""
                        INSERT INTO registry.sample_subject_resolution_revision(
                          resolution_revision_id,source_domain,source_record_id,resolution_sequence,
                          resolution_action,stable_subject_id,target_sample_point_id,source_version,
                          predecessor_revision_id,batch_id,item_sequence,before_sha256,after_sha256,
                          occurred_at,actor)
                        VALUES('11800000-0000-0000-0000-000000000111','MARKET',
                          'v118-polluted-current',1,'LINK','%s','%s',0,NULL,
                          '11800000-0000-0000-0000-000000000110',1,repeat('b',64),repeat('c',64),
                          now(),'migration-test')
                        """.formatted(currentStable, currentPoint));
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void removeCrossProjectionPollution() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL session_replication_role=replica");
                statement.execute("DELETE FROM registry.sample_subject_resolution_revision "
                        + "WHERE resolution_revision_id='11800000-0000-0000-0000-000000000111'");
                statement.execute("DELETE FROM registry.sample_subject_resolution_item "
                        + "WHERE batch_id='11800000-0000-0000-0000-000000000110'");
                statement.execute("DELETE FROM registry.sample_subject_resolution_batch "
                        + "WHERE batch_id='11800000-0000-0000-0000-000000000110'");
                statement.execute("DELETE FROM registry.sample_point_subject_identity "
                        + "WHERE subject_id='migration-legacy-stable'");
                statement.execute("DELETE FROM registry.sample_point WHERE sample_point_id IN "
                        + "('11800000-0000-0000-0000-000000000101',"
                        + "'11800000-0000-0000-0000-000000000102')");
                statement.execute("SET LOCAL session_replication_role=origin");
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }

    private enum CrossProjectionPollution {
        SAME_STABLE_DIFFERENT_POINT(
                "one stable subject maps to multiple sample points"),
        SAME_POINT_DIFFERENT_STABLE(
                "one sample point maps to multiple stable subjects");

        private final String expectedFailure;

        CrossProjectionPollution(String expectedFailure) {
            this.expectedFailure = expectedFailure;
        }

        String expectedFailure() {
            return expectedFailure;
        }
    }

    private String query(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertThatThrownBySql(String sql) throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(java.sql.SQLException.class);
    }

    private void assertThatRejectedAtDeferredConstraintCheck(String sql) throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(sql);
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> statement.execute("SET CONSTRAINTS ALL IMMEDIATE"))
                        .isInstanceOf(java.sql.SQLException.class);
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertRuntimeRoleCannotExecuteApprovedInventoryCorrection() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE cofco_app");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute("""
                                SELECT registry.correct_approved_market_inventory_resolution(
                                  '00000000-0000-0000-0000-000000000001'::uuid)
                                """))
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("permission denied for function");
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertRuntimeCanReadOverviewRegionSurplusContract() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE cofco_app");
                try (ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM overview.region_surplus_calculation_contract
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertRuntimeCannotMutateOverviewRegionSurplusContract() throws Exception {
        String[] statements = {
            "INSERT INTO overview.region_surplus_calculation_contract(version_code) VALUES ('permission-negative')",
            "UPDATE overview.region_surplus_calculation_contract SET name=name WHERE false",
            "DELETE FROM overview.region_surplus_calculation_contract WHERE false"
        };
        for (String sql : statements) {
            try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    statement.execute("SET LOCAL ROLE cofco_app");
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute(sql))
                            .isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("permission denied for table");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void assertRuntimeCanReadMarketInventoryGovernance() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE cofco_app");
                try (ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM market.market_inventory_governance
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isGreaterThanOrEqualTo(0);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertRuntimeCanReadMarketInventoryContract() throws Exception {
        try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL ROLE cofco_app");
                try (ResultSet result = statement.executeQuery("""
                        SELECT count(*)
                        FROM market.sample_point_inventory_contract
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isGreaterThanOrEqualTo(0);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertRuntimeCannotMutateMarketInventoryGovernance() throws Exception {
        String[] statements = {
            "INSERT INTO market.market_inventory_governance(record_id,status_code,reason_code) "
                    + "VALUES ('permission-negative','PENDING_REVIEW','permission-negative')",
            "UPDATE market.market_inventory_governance SET reason_code=reason_code WHERE false",
            "DELETE FROM market.market_inventory_governance WHERE false"
        };
        for (String sql : statements) {
            try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    statement.execute("SET LOCAL ROLE cofco_app");
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute(sql))
                            .isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("permission denied for table");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void assertRuntimeCanManageMarketInventoryGovernance() throws Exception {
        String[] statements = {
            """
            INSERT INTO market.market_inventory_governance(record_id,status_code,reason_code)
            SELECT record_id,'PENDING_REVIEW','SUBJECT_RESOLUTION_REQUIRED'
            FROM market.market_record WHERE false
            ON CONFLICT(record_id) DO UPDATE SET status_code='PENDING_REVIEW',
              reason_code=excluded.reason_code,sample_point_id=NULL,resolved_by=NULL,resolved_at=NULL
            """,
            "UPDATE market.market_inventory_governance SET reason_code=reason_code WHERE false",
            "DELETE FROM market.market_inventory_governance WHERE false"
        };
        for (String sql : statements) {
            try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    statement.execute("SET LOCAL ROLE cofco_app");
                    statement.execute(sql);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void assertRuntimeCannotMutateMarketInventoryContract() throws Exception {
        String[] statements = {
            "UPDATE market.sample_point_inventory_contract SET policy_attribute=policy_attribute WHERE false",
            "DELETE FROM market.sample_point_inventory_contract WHERE false"
        };
        for (String sql : statements) {
            try (Connection connection = DATABASE.openConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    statement.execute("SET LOCAL ROLE cofco_app");
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute(sql))
                            .isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("permission denied for table");
                } finally {
                    connection.rollback();
                }
            }
        }
    }
}
