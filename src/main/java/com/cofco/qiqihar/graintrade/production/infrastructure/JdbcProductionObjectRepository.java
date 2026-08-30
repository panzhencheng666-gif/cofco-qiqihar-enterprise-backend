package com.cofco.qiqihar.graintrade.production.infrastructure;

import com.cofco.qiqihar.graintrade.production.application.ProductionObjectDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectRepository;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectRoleDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionObjectView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcProductionObjectRepository implements ProductionObjectRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcProductionObjectRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean valid(ProductionObjectDraft draft) {
        if (count("SELECT count(*) FROM production.production_object_type_definition WHERE code=:code",
                    "code", draft.objectTypeId()) != 1
                || count("SELECT count(*) FROM platform.region WHERE code=:code",
                    "code", draft.regionCode()) != 1
                || count("SELECT count(*) FROM production.production_source_channel_definition WHERE code=:code",
                    "code", draft.sourceChannelId()) != 1) {
            return false;
        }
        List<String> products = draft.productIds().stream()
                .map(JdbcProductionObjectRepository::productCode).toList();
        if (products.stream().anyMatch(String::isBlank)
                || jdbc.sql("SELECT count(*) FROM platform.product WHERE code IN (:codes)")
                        .param("codes", products).query(Long.class).single() != products.size()) {
            return false;
        }
        List<String> cultivars = draft.cultivarIds().stream()
                .map(JdbcProductionObjectRepository::cultivarCode).toList();
        if (!cultivars.isEmpty()
                && jdbc.sql("""
                        SELECT count(*) FROM platform.cultivar
                        WHERE code IN (:cultivars) AND product_code IN (:products)
                        """).param("cultivars", cultivars).param("products", products)
                        .query(Long.class).single() != cultivars.size()) {
            return false;
        }
        for (ProductionObjectRoleDraft role : draft.roles()) {
            long matches = jdbc.sql("""
                    SELECT count(*) FROM production.production_business_role_definition
                    WHERE code=:code AND capability_template_version_id=:template
                    """).param("code", role.roleId())
                    .param("template", role.capabilityTemplateVersionId())
                    .query(Long.class).single();
            if (matches != 1) return false;
        }
        return true;
    }

    @Override
    public boolean conflicts(String objectId, ProductionObjectDraft draft) {
        return jdbc.sql("""
                SELECT count(*) FROM production.monitoring_object
                WHERE region_code=:region
                  AND lower(btrim(object_name))=lower(btrim(:name))
                  AND (CAST(:id AS uuid) IS NULL OR object_id<>CAST(:id AS uuid))
                """).param("region", draft.regionCode()).param("name", draft.objectName())
                .param("id", objectId).query(Long.class).single() > 0;
    }

    @Override
    public List<ProductionObjectView> findAll(Set<String> regionCodes) {
        if (regionCodes.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT object_id::text FROM production.monitoring_object
                WHERE region_code IN (:regions)
                ORDER BY object_name,object_id
                """).param("regions", regionCodes).query(String.class).list().stream()
                .map(this::required).toList();
    }

    @Override
    public Optional<ProductionObjectView> find(String objectId) {
        return jdbc.sql("""
                SELECT object_id::text FROM production.monitoring_object
                WHERE object_id=:id::uuid
                """).param("id", objectId).query(String.class).optional().map(this::required);
    }

    @Override
    public ProductionObjectView insert(
            String objectId, ProductionObjectDraft draft,
            String responsibleSubjectId, String responsiblePerson, Instant now) {
        jdbc.sql("""
                INSERT INTO production.monitoring_object(
                    object_id,object_name,object_type_code,region_code,source_channel_code,
                    responsible_subject_id,responsible_person,effective_from,effective_to,
                    validity_status,version,created_at,updated_at,updated_by)
                VALUES(:id::uuid,:name,:type,:region,:source,:subject,:person,:from,:to,
                    :status,0,:now,:now,:subject)
                """).param("id", objectId).param("name", draft.objectName().trim())
                .param("type", draft.objectTypeId()).param("region", draft.regionCode())
                .param("source", draft.sourceChannelId()).param("subject", responsibleSubjectId)
                .param("person", responsiblePerson).param("from", draft.effectiveFrom())
                .param("to", draft.effectiveTo()).param("status", draft.validityStatus())
                .param("now", Timestamp.from(now)).update();
        replaceChildren(objectId, draft);
        appendRevision(objectId, 0, draft, responsibleSubjectId, responsiblePerson, responsibleSubjectId, now);
        return required(objectId);
    }

    @Override
    public Optional<ProductionObjectView> update(
            String objectId, long expectedVersion, ProductionObjectDraft draft,
            String responsibleSubjectId, String responsiblePerson, String updatedBy, Instant now) {
        int updated = jdbc.sql("""
                UPDATE production.monitoring_object
                SET object_name=:name,object_type_code=:type,region_code=:region,
                    source_channel_code=:source,effective_from=:from,effective_to=:to,
                    validity_status=:status,version=version+1,updated_at=:now,updated_by=:updatedBy
                WHERE object_id=:id::uuid AND version=:version
                """).param("name", draft.objectName().trim()).param("type", draft.objectTypeId())
                .param("region", draft.regionCode()).param("source", draft.sourceChannelId())
                .param("from", draft.effectiveFrom()).param("to", draft.effectiveTo())
                .param("status", draft.validityStatus()).param("now", Timestamp.from(now))
                .param("updatedBy", updatedBy).param("id", objectId).param("version", expectedVersion).update();
        if (updated == 0) return Optional.empty();
        replaceChildren(objectId, draft);
        appendRevision(objectId, expectedVersion + 1, draft,
                responsibleSubjectId, responsiblePerson, updatedBy, now);
        return find(objectId);
    }

    private ProductionObjectView required(String objectId) {
        Header header = jdbc.sql("""
                SELECT object.object_id::text,object.object_name,object.object_type_code,type.name,
                       object.region_code,region.name,object.source_channel_code,source.name,
                       object.responsible_subject_id,object.responsible_person,
                       object.effective_from,object.effective_to,object.validity_status,object.version
                FROM production.monitoring_object object
                JOIN production.production_object_type_definition type ON type.code=object.object_type_code
                JOIN platform.region region ON region.code=object.region_code
                JOIN production.production_source_channel_definition source ON source.code=object.source_channel_code
                WHERE object.object_id=:id::uuid
                """).param("id", objectId).query((row, index) -> new Header(
                        row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getString(6), row.getString(7), row.getString(8),
                        row.getString(9), row.getString(10), row.getObject(11, LocalDate.class),
                        row.getObject(12, LocalDate.class), row.getString(13), row.getLong(14))).single();
        List<NamedCode> products = jdbc.sql("""
                SELECT product.code,product.name
                FROM production.monitoring_object_product assignment
                JOIN platform.product product ON product.code=assignment.product_code
                WHERE assignment.object_id=:id::uuid ORDER BY product.sort_order,product.code
                """).param("id", objectId).query((row, index) -> new NamedCode(
                        productId(row.getString(1)), row.getString(2))).list();
        List<NamedCode> cultivars = jdbc.sql("""
                SELECT cultivar.code,cultivar.name
                FROM production.monitoring_object_cultivar assignment
                JOIN platform.cultivar cultivar ON cultivar.code=assignment.cultivar_code
                WHERE assignment.object_id=:id::uuid ORDER BY cultivar.product_code,cultivar.sort_order,cultivar.code
                """).param("id", objectId).query((row, index) -> new NamedCode(
                        row.getString(1).toLowerCase(Locale.ROOT).replace('_', '-'), row.getString(2))).list();
        List<ProductionObjectView.Role> roles = jdbc.sql("""
                SELECT role.code,role.name,assignment.effective_from,assignment.effective_to,
                       assignment.capability_template_version_id
                FROM production.monitoring_object_role_assignment assignment
                JOIN production.production_business_role_definition role ON role.code=assignment.role_code
                WHERE assignment.object_id=:id::uuid ORDER BY role.sort_order,role.code
                """).param("id", objectId).query((row, index) -> new ProductionObjectView.Role(
                        row.getString(1), row.getString(2), row.getObject(3, LocalDate.class),
                        row.getObject(4, LocalDate.class), row.getString(5))).list();
        return new ProductionObjectView(
                header.id(), header.name(), header.typeId(), header.typeLabel(),
                header.regionCode(), header.regionName(),
                products.stream().map(NamedCode::code).toList(),
                products.stream().map(NamedCode::name).toList(),
                cultivars.stream().map(NamedCode::code).toList(),
                cultivars.stream().map(NamedCode::name).toList(),
                header.sourceId(), header.sourceLabel(), header.responsibleSubjectId(),
                header.responsiblePerson(), header.effectiveFrom(), header.effectiveTo(),
                header.status(), roles, header.version());
    }

    private void replaceChildren(String objectId, ProductionObjectDraft draft) {
        jdbc.sql("DELETE FROM production.monitoring_object_role_assignment WHERE object_id=:id::uuid")
                .param("id", objectId).update();
        jdbc.sql("DELETE FROM production.monitoring_object_cultivar WHERE object_id=:id::uuid")
                .param("id", objectId).update();
        jdbc.sql("DELETE FROM production.monitoring_object_product WHERE object_id=:id::uuid")
                .param("id", objectId).update();
        draft.productIds().forEach(product -> jdbc.sql("""
                INSERT INTO production.monitoring_object_product(object_id,product_code)
                VALUES(:id::uuid,:code)
                """).param("id", objectId).param("code", productCode(product)).update());
        draft.cultivarIds().forEach(cultivar -> jdbc.sql("""
                INSERT INTO production.monitoring_object_cultivar(object_id,cultivar_code)
                VALUES(:id::uuid,:code)
                """).param("id", objectId).param("code", cultivarCode(cultivar)).update());
        draft.roles().forEach(role -> jdbc.sql("""
                INSERT INTO production.monitoring_object_role_assignment(
                    object_id,role_code,effective_from,effective_to,capability_template_version_id)
                VALUES(:id::uuid,:role,:from,:to,:template)
                """).param("id", objectId).param("role", role.roleId())
                .param("from", role.effectiveFrom()).param("to", role.effectiveTo())
                .param("template", role.capabilityTemplateVersionId()).update());
    }

    private void appendRevision(
            String objectId, long version, ProductionObjectDraft draft,
            String responsibleSubjectId, String responsiblePerson, String recordedBy, Instant now) {
        try {
            String snapshot = objectMapper.writeValueAsString(
                    new RevisionSnapshot(draft, responsibleSubjectId, responsiblePerson));
            jdbc.sql("""
                    INSERT INTO production.monitoring_object_revision(
                        revision_id,object_id,object_version,snapshot_json,recorded_at,recorded_by)
                    VALUES(:revision::uuid,:id::uuid,:version,CAST(:snapshot AS jsonb),:now,:actor)
                    """).param("revision", UUID.randomUUID().toString()).param("id", objectId)
                    .param("version", version).param("snapshot", snapshot)
                    .param("now", Timestamp.from(now)).param("actor", recordedBy).update();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize production object revision", exception);
        }
    }

    private long count(String sql, String parameter, String value) {
        return jdbc.sql(sql).param(parameter, value).query(Long.class).single();
    }

    private static String productCode(String id) {
        return switch (id) {
            case "corn" -> "CORN";
            case "soybean" -> "SOYBEAN";
            case "paddy" -> "RICE";
            default -> "";
        };
    }

    private static String productId(String code) {
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "CORN" -> "corn";
            case "SOYBEAN" -> "soybean";
            case "RICE" -> "paddy";
            default -> code.toLowerCase(Locale.ROOT);
        };
    }

    private static String cultivarCode(String id) {
        return id.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private record Header(
            String id, String name, String typeId, String typeLabel,
            String regionCode, String regionName, String sourceId, String sourceLabel,
            String responsibleSubjectId, String responsiblePerson,
            LocalDate effectiveFrom, LocalDate effectiveTo, String status, long version) {
    }

    private record NamedCode(String code, String name) {
    }

    private record RevisionSnapshot(
            ProductionObjectDraft object,
            String responsibleSubjectId,
            String responsiblePerson) {
    }
}
