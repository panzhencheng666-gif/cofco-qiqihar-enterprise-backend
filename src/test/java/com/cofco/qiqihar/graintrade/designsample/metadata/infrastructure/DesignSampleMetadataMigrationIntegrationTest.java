package com.cofco.qiqihar.graintrade.designsample.metadata.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class DesignSampleMetadataMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void migratesAnImmutableDigestBoundCatalogWithRuntimeReadOnlyAccess() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(count(jdbc, "platform.design_sample_domain_definition")).isEqualTo(5);
        assertThat(count(jdbc, "platform.design_sample_product_definition")).isEqualTo(7);
        assertThat(count(jdbc, "platform.design_sample_object_type_definition")).isEqualTo(23);
        assertThat(count(jdbc, "platform.design_sample_context")).isEqualTo(55);

        String storedDigest = jdbc.sql("""
                        SELECT contract_digest
                        FROM platform.design_sample_contract
                        WHERE active
                        """)
                .query(String.class)
                .single();
        String currentDigest = jdbc.sql("SELECT platform.current_design_sample_contract_digest()")
                .query(String.class)
                .single();
        assertThat(storedDigest)
                .matches("sha256:[a-f0-9]{64}")
                .isEqualTo(currentDigest);

        jdbc.sql("""
                INSERT INTO platform.design_sample_contract(
                    contract_version, contract_digest, active, activated_at)
                VALUES ('design-sample-fields-v3',
                        'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        false, CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.design_sample_domain_definition(
                    contract_version, code, name, description, aliases, sort_order)
                VALUES ('design-sample-fields-v3', 'FUTURE', '未来域', '未启用合同', '[]', 999)
                """).update();
        assertThat(jdbc.sql("SELECT platform.current_design_sample_contract_digest()")
                        .query(String.class)
                        .single())
                .isEqualTo(storedDigest);

        assertThat(privilege(jdbc, "platform.design_sample_contract", "SELECT")).isTrue();
        assertThat(privilege(jdbc, "platform.design_sample_field_definition", "SELECT")).isTrue();
        assertThat(privilege(jdbc, "platform.design_sample_context", "INSERT")).isFalse();
        assertThat(privilege(jdbc, "platform.design_sample_field_applicability", "UPDATE")).isFalse();
    }

    private long count(JdbcClient jdbc, String relation) {
        return jdbc.sql("SELECT count(*) FROM " + relation).query(Long.class).single();
    }

    private boolean privilege(JdbcClient jdbc, String relation, String privilege) {
        return jdbc.sql("SELECT has_table_privilege('qiqihar_enterprise_runtime', :relation, :privilege)")
                .param("relation", relation)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single();
    }
}
