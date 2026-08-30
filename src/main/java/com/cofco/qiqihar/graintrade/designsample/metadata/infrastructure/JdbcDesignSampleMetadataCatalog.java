package com.cofco.qiqihar.graintrade.designsample.metadata.infrastructure;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot.DomainDefinition;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot.ObjectTypeDefinition;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot.ProductDefinition;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot.SupportedContext;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleMetadataCatalog;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleFieldDefinition;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDesignSampleMetadataCatalog implements DesignSampleMetadataCatalog {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcDesignSampleMetadataCatalog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public DesignSampleContractSnapshot loadActiveContract() {
        ContractRow contract = jdbc.sql("""
                        SELECT contract_version, contract_digest,
                               platform.current_design_sample_contract_digest() AS current_digest
                        FROM platform.design_sample_contract
                        WHERE active
                        """)
                .query((result, row) -> new ContractRow(
                        result.getString("contract_version"),
                        result.getString("contract_digest"),
                        result.getString("current_digest")))
                .single();
        if (!contract.storedDigest().equals(contract.currentDigest())) invalidContract();

        List<DomainDefinition> domains = jdbc.sql("""
                        SELECT code, name, description, aliases, sort_order
                        FROM platform.design_sample_domain_definition
                        WHERE contract_version = :version
                        ORDER BY sort_order, code
                        """)
                .param("version", contract.version())
                .query((result, row) -> new DomainDefinition(
                        result.getString("code"),
                        result.getString("name"),
                        result.getString("description"),
                        strings(result.getString("aliases")),
                        result.getInt("sort_order")))
                .list();
        List<ProductDefinition> products = jdbc.sql("""
                        SELECT code, name, aliases, sort_order
                        FROM platform.design_sample_product_definition
                        WHERE contract_version = :version
                        ORDER BY sort_order, code
                        """)
                .param("version", contract.version())
                .query((result, row) -> new ProductDefinition(
                        result.getString("code"),
                        result.getString("name"),
                        strings(result.getString("aliases")),
                        result.getInt("sort_order")))
                .list();
        List<ObjectTypeDefinition> objectTypes = jdbc.sql("""
                        SELECT domain_code, code, name, aliases, sort_order
                        FROM platform.design_sample_object_type_definition
                        WHERE contract_version = :version
                        ORDER BY domain_code, sort_order, code
                        """)
                .param("version", contract.version())
                .query((result, row) -> new ObjectTypeDefinition(
                        result.getString("domain_code"),
                        result.getString("code"),
                        result.getString("name"),
                        strings(result.getString("aliases")),
                        result.getInt("sort_order")))
                .list();
        List<SupportedContext> contexts = jdbc.sql("""
                        SELECT domain_code, product_code, object_type_code, sort_order
                        FROM platform.design_sample_context
                        WHERE contract_version = :version
                        ORDER BY sort_order
                        """)
                .param("version", contract.version())
                .query((result, row) -> new SupportedContext(
                        result.getString("domain_code"),
                        result.getString("product_code"),
                        result.getString("object_type_code"),
                        result.getInt("sort_order")))
                .list();

        Map<DesignSampleContext, List<DesignSampleFieldDefinition>> fieldsByContext =
                new LinkedHashMap<>();
        contexts.forEach(context -> fieldsByContext.put(context.key(), new ArrayList<>()));
        Map<String, DesignSampleFieldDefinition> fieldsByCode = new LinkedHashMap<>();
        List<ContextField> contextFields = jdbc.sql("""
                        SELECT applicability.domain_code, applicability.product_code,
                               applicability.object_type_code, definition.code,
                               definition.section_code, definition.label, definition.description,
                               definition.value_type, definition.numeric_precision,
                               definition.numeric_scale, definition.max_length, definition.unit,
                               definition.enum_options, definition.required, definition.nullable,
                               definition.default_value, definition.editable,
                               definition.minimum_value, definition.maximum_value,
                               applicability.group_code, applicability.sort_order,
                               definition.analysis_role
                        FROM platform.design_sample_field_applicability applicability
                        JOIN platform.design_sample_context context
                          ON context.contract_version = applicability.contract_version
                         AND context.domain_code = applicability.domain_code
                         AND context.product_code = applicability.product_code
                         AND context.object_type_code = applicability.object_type_code
                        JOIN platform.design_sample_field_definition definition
                          ON definition.contract_version = applicability.contract_version
                         AND definition.code = applicability.field_code
                        WHERE applicability.contract_version = :version
                        ORDER BY context.sort_order, applicability.sort_order, definition.code
                        """)
                .param("version", contract.version())
                .query(this::contextField)
                .list();
        contextFields.forEach(row -> {
            List<DesignSampleFieldDefinition> fields = fieldsByContext.get(row.context());
            if (fields == null) invalidContract();
            fields.add(row.field());
            fieldsByCode.putIfAbsent(row.field().code(), row.field());
        });
        if (fieldsByContext.values().stream().anyMatch(List::isEmpty)) invalidContract();

        return new DesignSampleContractSnapshot(
                contract.version(),
                contract.storedDigest(),
                domains,
                products,
                objectTypes,
                contexts,
                fieldsByContext,
                fieldsByCode);
    }

    private ContextField contextField(ResultSet result, int row) throws SQLException {
        DesignSampleContext context = new DesignSampleContext(
                result.getString("domain_code"),
                result.getString("product_code"),
                result.getString("object_type_code"));
        DesignSampleFieldDefinition field = new DesignSampleFieldDefinition(
                result.getString("code"),
                result.getString("section_code"),
                result.getString("label"),
                result.getString("description"),
                result.getString("value_type"),
                integer(result, "numeric_precision"),
                integer(result, "numeric_scale"),
                integer(result, "max_length"),
                result.getString("unit"),
                strings(result.getString("enum_options")),
                result.getBoolean("required"),
                result.getBoolean("nullable"),
                jsonValue(result.getString("default_value")),
                result.getBoolean("editable"),
                decimal(result, "minimum_value"),
                decimal(result, "maximum_value"),
                result.getString("group_code"),
                result.getInt("sort_order"),
                result.getString("analysis_role"));
        return new ContextField(context, field);
    }

    private Integer integer(ResultSet result, String column) throws SQLException {
        return result.getObject(column, Integer.class);
    }

    private String decimal(ResultSet result, String column) throws SQLException {
        BigDecimal value = result.getBigDecimal(column);
        return value == null ? null : value.toPlainString();
    }

    private Object jsonValue(String value) {
        return value == null ? null : json.readTree(value);
    }

    private List<String> strings(String value) {
        JsonNode node = json.readTree(value);
        if (!node.isArray()) invalidContract();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) invalidContract();
            values.add(item.asText());
        }
        if (values.size() != values.stream().distinct().count()) invalidContract();
        return List.copyOf(values);
    }

    private static void invalidContract() {
        throw new ServerContractException(
                "DESIGN_SAMPLE_METADATA_INVALID",
                "设计样本点元数据合同无效");
    }

    private record ContractRow(String version, String storedDigest, String currentDigest) {}

    private record ContextField(
            DesignSampleContext context,
            DesignSampleFieldDefinition field) {}
}
