package com.cofco.qiqihar.graintrade.logistics.infrastructure;

import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDefinitionView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRecordView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRepository;
import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLogisticsRepository implements LogisticsRepository {
    private static final Pattern STORAGE_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private final JdbcClient jdbc;

    public JdbcLogisticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LogisticsDefinitionView definition(String productCode) {
        List<FieldMeta> fields = fields(productCode);
        if (fields.isEmpty()) return null;
        Map<String, List<LogisticsDefinitionView.Option>> options = new LinkedHashMap<>();
        jdbc.sql("""
                WITH eligible AS (
                  SELECT definition.code,definition.option_source FROM platform.logistics_core_field_definition definition
                  JOIN platform.logistics_core_field_applicability applicability ON applicability.field_code=definition.code
                  WHERE applicability.product_code=:product
                )
                SELECT option.field_code,option.value,option.label,option.sort_order
                FROM platform.logistics_core_field_option option JOIN eligible ON eligible.code=option.field_code
                UNION ALL
                SELECT eligible.code,period.code,period.name,period.sort_order FROM eligible
                  CROSS JOIN platform.business_period period WHERE eligible.option_source='BUSINESS_PERIOD'
                UNION ALL
                SELECT eligible.code,node.node_code,node.node_name,node.node_id::integer FROM eligible
                  CROSS JOIN logistics.logistics_node node WHERE eligible.option_source='LOGISTICS_NODE' AND node.active
                UNION ALL
                SELECT eligible.code,mode.code,mode.name,mode.sort_order FROM eligible
                  CROSS JOIN platform.transport_mode mode WHERE eligible.option_source='TRANSPORT_MODE'
                ORDER BY field_code,sort_order
                """).param("product", productCode).query((row, index) -> new OptionRow(
                        row.getString("field_code"), row.getString("value"), row.getString("label"),
                        row.getInt("sort_order"))).list().forEach(option -> options
                        .computeIfAbsent(option.field, key -> new ArrayList<>())
                        .add(new LogisticsDefinitionView.Option(option.value, option.label, option.order)));
        List<LogisticsDefinitionView.Action> actions = jdbc.sql("""
                SELECT code,label,action_scope,sort_order FROM platform.page_action
                WHERE product_code=:product AND business_domain='LOGISTICS' AND page_kind='MONITORING'
                ORDER BY sort_order
                """).param("product", productCode).query((row, index) -> new LogisticsDefinitionView.Action(
                        row.getString("code"), row.getString("label"), row.getString("action_scope"),
                        row.getInt("sort_order"))).list();
        return new LogisticsDefinitionView(productCode, fields.stream().map(field ->
                new LogisticsDefinitionView.Field(field.code, field.label, field.control,
                        field.unit, field.precision, field.scale, field.required,
                        field.control.startsWith("READONLY"), field.order,
                        List.copyOf(options.getOrDefault(field.code, List.of())))).toList(), actions);
    }

    @Override
    public boolean validDraft(LogisticsDraft draft, LocalDate today) {
        List<FieldMeta> fields = fields(draft.productCode());
        if (fields.isEmpty()) return false;
        Map<String, FieldMeta> byCode = new LinkedHashMap<>();
        fields.forEach(field -> byCode.put(field.code, field));
        if (draft.values().keySet().stream().anyMatch(code -> !byCode.containsKey(code)
                || byCode.get(code).control.startsWith("READONLY"))) return false;
        if (fields.stream().filter(field -> field.required && !field.control.startsWith("READONLY"))
                .anyMatch(field -> blank(draft.values().get(field.code)))) return false;
        LogisticsDefinitionView definition = definition(draft.productCode());
        Map<String, Set<String>> optionValues = new LinkedHashMap<>();
        definition.fields().forEach(field -> optionValues.put(field.code(), field.options().stream()
                .map(LogisticsDefinitionView.Option::value).collect(java.util.stream.Collectors.toSet())));
        for (FieldMeta field : fields) {
            if (!validBinding(field)) return false;
            String value = draft.values().get(field.code);
            if (blank(value)) continue;
            try {
                if (field.control.equals("DECIMAL")) {
                    BigDecimal decimal = decimal(value, field);
                    if (decimal.signum() < 0 || decimal.precision() > field.precision || decimal.scale() > field.scale) return false;
                } else if (field.control.equals("DATE") && LocalDate.parse(value).isAfter(today)) return false;
                else if (field.control.equals("SELECT") && !optionValues.get(field.code).contains(value)) return false;
            } catch (RuntimeException exception) {
                return false;
            }
        }
        String origin = valueForBinding(fields, draft.values(), "EVENT.origin_node_code");
        String destination = valueForBinding(fields, draft.values(), "EVENT.destination_node_code");
        return origin != null && !origin.equals(destination);
    }

    @Override
    public boolean actionAllowed(String productCode, LogisticsStatus status, String actionCode) {
        if (actionCode.equals("NEW")) {
            return Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM platform.page_action
                      WHERE product_code=:product AND business_domain='LOGISTICS'
                        AND page_kind='MONITORING' AND code='NEW')
                    """).param("product", productCode).query(Boolean.class).single());
        }
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM platform.logistics_action_applicability policy
                  JOIN platform.page_action action ON action.product_code=policy.product_code
                    AND action.business_domain='LOGISTICS' AND action.page_kind='MONITORING'
                    AND action.code=policy.action_code
                  WHERE policy.product_code=:product AND policy.status_code=:status
                    AND policy.action_code=:action)
                """).param("product", productCode).param("status", status.name()).param("action", actionCode)
                .query(Boolean.class).single());
    }

    @Override
    public Set<String> regionsForDraft(LogisticsDraft draft) {
        return regionsForNodes(List.of(draft.values().get("LOG_ORIGIN"), draft.values().get("LOG_DESTINATION")));
    }

    @Override
    public Set<String> regionsForRecord(String id) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT event.origin_region_code FROM logistics.route_event event
                WHERE event.event_id::text = :id
                UNION
                SELECT event.destination_region_code FROM logistics.route_event event
                WHERE event.event_id::text = :id
                """).param("id", id).query(String.class).list());
    }

    private Set<String> regionsForNodes(List<String> nodeCodes) {
        List<String> present = nodeCodes.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (present.size() != 2) return Set.of();
        return new LinkedHashSet<>(jdbc.sql("SELECT region_code FROM logistics.logistics_node WHERE node_code IN (:nodes)")
                .param("nodes", present).query(String.class).list());
    }

    @Override
    public PagedResult<LogisticsRecordView> findPage(
            String product, int page, int size, Map<String, String> filters, Set<String> authorizedRegionCodes) {
        SqlFilter filter = filter(product, filters, authorizedRegionCodes);
        long total = jdbc.sql("SELECT count(*) FROM logistics.route_event e " + filter.sql)
                .params(filter.params).query(Long.class).single();
        List<String> ids = jdbc.sql("""
                SELECT e.event_id::text FROM logistics.route_event e %s
                ORDER BY e.collection_date DESC,e.event_id LIMIT :limit OFFSET :offset
                """.formatted(filter.sql)).params(filter.params).param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size)).query(String.class).list();
        return new PagedResult<>(findAll(ids), page, size, total);
    }

    @Override
    public LogisticsRecordView find(String id) {
        List<LogisticsRecordView> records = findAll(List.of(id));
        return records.isEmpty() ? null : records.getFirst();
    }

    @Override
    public LogisticsRecordView insert(String id, LogisticsDraft draft, String actor, Instant now) {
        writeEvent(id, null, draft, actor, now);
        writeDynamicValues(id, draft);
        return find(id);
    }

    @Override
    public LogisticsRecordView update(
            String id, long version, LogisticsDraft draft, String actor, Instant now) {
        writeEvent(id, version, draft, actor, now);
        writeDynamicValues(id, draft);
        return find(id);
    }

    @Override
    public LogisticsRecordView transition(
            String id, long version, LogisticsStatus status, String reason, String actor, Instant now) {
        int count = jdbc.sql("""
                UPDATE logistics.route_event SET status_code=:status,return_reason=:reason,
                  last_modified_by=:actor,updated_at=:now,version=version+1
                WHERE event_id::text=:id AND version=:version
                """).param("status", status.name()).param("reason", reason).param("actor", actor)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("id", id)
                .param("version", version).update();
        require(count);
        return find(id);
    }

    private List<LogisticsRecordView> findAll(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        List<Header> headers = jdbc.sql("""
                SELECT event_id::text id,product_code,status_code,return_reason,version
                FROM logistics.route_event WHERE event_id::text IN (:ids)
                """).param("ids", ids).query((row, index) -> new Header(row.getString("id"),
                        row.getString("product_code"), LogisticsStatus.valueOf(row.getString("status_code")),
                        row.getString("return_reason"), row.getLong("version"))).list();
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        Map<String, Map<String, String>> displayValues = new LinkedHashMap<>();
        jdbc.sql("""
                WITH raw_value AS (
                  SELECT event.event_id::text id,definition.code,definition.option_source,
                    CASE split_part(definition.binding,'.',1)
                    WHEN 'EVENT' THEN to_jsonb(event)->>split_part(definition.binding,'.',2)
                    WHEN 'READONLY' THEN to_jsonb(event)->>split_part(definition.binding,'.',2)
                    WHEN 'FACT' THEN fact.value::text
                    WHEN 'EXTENSION' THEN extension.value
                    END value,applicability.sort_order
                  FROM logistics.route_event event
                  JOIN platform.logistics_core_field_applicability applicability ON applicability.product_code=event.product_code
                  JOIN platform.logistics_core_field_definition definition ON definition.code=applicability.field_code
                  LEFT JOIN logistics.route_fact fact ON fact.event_id=event.event_id
                    AND fact.fact_code=split_part(definition.binding,'.',2)
                  LEFT JOIN logistics.route_event_core_value extension ON extension.event_id=event.event_id
                    AND extension.field_code=definition.code
                  WHERE event.event_id::text IN (:ids)
                )
                SELECT raw.id,raw.code,raw.value,
                  COALESCE(option.label,period.name,node.node_name,mode.name,raw.value) display_value
                FROM raw_value raw
                LEFT JOIN platform.logistics_core_field_option option
                  ON option.field_code=raw.code AND option.value=raw.value
                LEFT JOIN platform.business_period period
                  ON raw.option_source='BUSINESS_PERIOD' AND period.code=raw.value
                LEFT JOIN logistics.logistics_node node
                  ON raw.option_source='LOGISTICS_NODE' AND node.node_code=raw.value
                LEFT JOIN platform.transport_mode mode
                  ON raw.option_source='TRANSPORT_MODE' AND mode.code=raw.value
                ORDER BY raw.id,raw.sort_order
                """).param("ids", ids).query((row, index) -> new ValueRow(row.getString("id"),
                        row.getString("code"), row.getString("value"), row.getString("display_value")))
                .list().forEach(value -> {
                            if (value.value != null) values.computeIfAbsent(value.id, key -> new LinkedHashMap<>())
                                    .put(value.code, value.value);
                            if (value.displayValue != null) displayValues
                                    .computeIfAbsent(value.id, key -> new LinkedHashMap<>())
                                    .put(value.code, value.displayValue);
                        });
        Map<String, List<String>> allowedActions = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT event.event_id::text id,policy.action_code
                FROM logistics.route_event event
                JOIN platform.logistics_action_applicability policy
                  ON policy.product_code=event.product_code AND policy.status_code=event.status_code
                JOIN platform.page_action action ON action.product_code=policy.product_code
                  AND action.business_domain='LOGISTICS' AND action.page_kind='MONITORING'
                  AND action.code=policy.action_code
                WHERE event.event_id::text IN (:ids) ORDER BY event.event_id,action.sort_order
                """).param("ids", ids).query((row, index) -> Map.entry(
                        row.getString("id"), row.getString("action_code"))).list().forEach(entry ->
                                allowedActions.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        Map<String, Header> byId = new LinkedHashMap<>();
        headers.forEach(header -> byId.put(header.id, header));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).map(header ->
                new LogisticsRecordView(header.id, header.product,
                        Map.copyOf(values.getOrDefault(header.id, Map.of())),
                        Map.copyOf(displayValues.getOrDefault(header.id, Map.of())), header.status,
                        header.reason, List.copyOf(allowedActions.getOrDefault(header.id, List.of())), header.version)).toList();
    }

    private void writeEvent(String id, Long expectedVersion, LogisticsDraft draft, String actor, Instant now) {
        List<FieldMeta> fields = fields(draft.productCode()).stream()
                .filter(field -> field.binding.startsWith("EVENT.")).toList();
        Map<String, Object> parameters = new LinkedHashMap<>();
        List<String> assignments = new ArrayList<>();
        for (FieldMeta field : fields) {
            String column = storageKey(field.binding);
            String parameter = "field_" + field.code.toLowerCase(java.util.Locale.ROOT);
            assignments.add(column + "=" + parameterExpression(parameter, field.control));
            parameters.put(parameter, databaseValue(draft.values().get(field.code), field));
        }
        String origin = valueForBinding(fields, draft.values(), "EVENT.origin_node_code");
        String destination = valueForBinding(fields, draft.values(), "EVENT.destination_node_code");
        parameters.put("originCode", origin);
        parameters.put("destinationCode", destination);
        parameters.put("id", id);
        parameters.put("product", draft.productCode());
        parameters.put("actor", actor);
        parameters.put("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        if (expectedVersion == null) {
            List<String> columns = new ArrayList<>(List.of("event_id", "product_code"));
            List<String> values = new ArrayList<>(List.of("CAST(:id AS uuid)", ":product"));
            for (int index = 0; index < fields.size(); index++) {
                columns.add(storageKey(fields.get(index).binding));
                String parameter = "field_" + fields.get(index).code.toLowerCase(java.util.Locale.ROOT);
                values.add(parameterExpression(parameter, fields.get(index).control));
            }
            columns.addAll(List.of("origin_region_code", "destination_region_code", "origin_node_id", "destination_node_id",
                    "reported_at", "status_code", "created_by", "last_modified_by", "created_at", "updated_at"));
            values.addAll(List.of("(SELECT region_code FROM logistics.logistics_node WHERE node_code=:originCode)",
                    "(SELECT region_code FROM logistics.logistics_node WHERE node_code=:destinationCode)",
                    "(SELECT node_id FROM logistics.logistics_node WHERE node_code=:originCode)",
                    "(SELECT node_id FROM logistics.logistics_node WHERE node_code=:destinationCode)",
                    ":now", "'DRAFT'", ":actor", ":actor", ":now", ":now"));
            jdbc.sql("INSERT INTO logistics.route_event(" + String.join(",", columns) + ") VALUES(" +
                    String.join(",", values) + ")").params(parameters).update();
        } else {
            assignments.addAll(List.of(
                    "origin_region_code=(SELECT region_code FROM logistics.logistics_node WHERE node_code=:originCode)",
                    "destination_region_code=(SELECT region_code FROM logistics.logistics_node WHERE node_code=:destinationCode)",
                    "origin_node_id=(SELECT node_id FROM logistics.logistics_node WHERE node_code=:originCode)",
                    "destination_node_id=(SELECT node_id FROM logistics.logistics_node WHERE node_code=:destinationCode)",
                    "reported_at=:now", "status_code='DRAFT'", "return_reason=NULL", "last_modified_by=:actor",
                    "updated_at=:now", "version=version+1"));
            parameters.put("version", expectedVersion);
            int count = jdbc.sql("UPDATE logistics.route_event SET " + String.join(",", assignments)
                    + " WHERE event_id::text=:id AND version=:version").params(parameters).update();
            require(count);
        }
    }

    private void writeDynamicValues(String id, LogisticsDraft draft) {
        List<FieldMeta> fields = fields(draft.productCode());
        jdbc.sql("DELETE FROM logistics.route_fact WHERE event_id::text=:id").param("id", id).update();
        jdbc.sql("DELETE FROM logistics.route_event_core_value WHERE event_id::text=:id").param("id", id).update();
        for (FieldMeta field : fields) {
            String value = draft.values().get(field.code);
            if (blank(value)) continue;
            if (field.binding.startsWith("FACT.")) {
                jdbc.sql("""
                        INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code)
                        VALUES(CAST(:id AS uuid),:code,:value,:unit)
                        """).param("id", id).param("code", storageKey(field.binding))
                        .param("value", decimal(value, field)).param("unit", field.unit).update();
            } else if (field.binding.startsWith("EXTENSION.")) {
                jdbc.sql("""
                        INSERT INTO logistics.route_event_core_value(event_id,field_code,value)
                        VALUES(CAST(:id AS uuid),:code,:value)
                        """).param("id", id).param("code", field.code).param("value", value).update();
            }
        }
    }

    private List<FieldMeta> fields(String productCode) {
        return jdbc.sql("""
                SELECT definition.code,definition.label,definition.control_type,definition.binding,
                  definition.option_source,definition.unit,definition.decimal_precision,definition.decimal_scale,
                  definition.required,applicability.sort_order
                FROM platform.logistics_core_field_applicability applicability
                JOIN platform.logistics_core_field_definition definition ON definition.code=applicability.field_code
                WHERE applicability.product_code=:product ORDER BY applicability.sort_order
                """).param("product", productCode).query((row, index) -> new FieldMeta(row.getString("code"),
                        row.getString("label"), row.getString("control_type"), row.getString("binding"),
                        row.getString("option_source"), row.getString("unit"),
                        (Integer) row.getObject("decimal_precision"), (Integer) row.getObject("decimal_scale"),
                        row.getBoolean("required"), row.getInt("sort_order"))).list();
    }

    private boolean validBinding(FieldMeta field) {
        if (field.binding == null || !field.binding.contains(".")) return false;
        String prefix = field.binding.substring(0, field.binding.indexOf('.'));
        String key;
        try { key = storageKey(field.binding); }
        catch (IllegalArgumentException exception) { return false; }
        if (field.control.startsWith("READONLY")) return prefix.equals("READONLY") && eventColumn(key);
        if (prefix.equals("EVENT")) return eventColumn(key);
        if (prefix.equals("FACT")) return Set.of("ROUTE_VOLUME", "FREIGHT_RATE", "TRANSIT_TIME").contains(key);
        return prefix.equals("EXTENSION");
    }

    private boolean eventColumn(String column) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_schema='logistics' AND table_name='route_event' AND column_name=:column)
                """).param("column", column).query(Boolean.class).single());
    }

    private static String valueForBinding(List<FieldMeta> fields, Map<String, String> values, String binding) {
        return fields.stream().filter(field -> field.binding.equals(binding)).findFirst()
                .map(field -> values.get(field.code)).orElse(null);
    }

    private static String storageKey(String binding) {
        int separator = binding.indexOf('.');
        if (separator < 1 || separator == binding.length() - 1) throw new IllegalArgumentException("Invalid logistics binding");
        String key = binding.substring(separator + 1);
        if (!STORAGE_KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid logistics binding");
        return key;
    }

    private static String parameterExpression(String parameter, String control) {
        return control.equals("DATE") ? "CAST(:" + parameter + " AS date)" : ":" + parameter;
    }

    private static Object databaseValue(String value, FieldMeta field) {
        return field.control.equals("DECIMAL") ? decimal(value, field) : value;
    }

    private static BigDecimal decimal(String value, FieldMeta field) {
        if (field.precision == null || field.scale == null) {
            throw new IllegalStateException("Logistics decimal metadata is incomplete: " + field.code);
        }
        return PlainDecimal.parse(value, field.precision - field.scale, field.scale, "INVALID_LOGISTICS_RECORD");
    }

    private static void require(int count) {
        if (count == 0) throw new ConflictException("LOGISTICS_RECORD_VERSION_CONFLICT", "Logistics record has changed");
    }

    private static SqlFilter filter(
            String product, Map<String, String> filters, Set<String> authorizedRegionCodes) {
        StringBuilder sql = new StringBuilder("WHERE e.product_code=:product");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("product", product);
        if (!authorizedRegionCodes.contains("*")) {
            if (authorizedRegionCodes.isEmpty()) sql.append(" AND 1=0");
            else {
                sql.append(" AND e.origin_region_code IN (:authorizedRegions)")
                        .append(" AND e.destination_region_code IN (:authorizedRegions)");
                params.put("authorizedRegions", authorizedRegionCodes);
            }
        }
        filters.forEach((key, value) -> {
            switch (key) {
                case "status" -> sql.append(" AND e.status_code=:status");
                case "regionCode" -> sql.append(" AND (e.origin_region_code=:regionCode OR e.destination_region_code=:regionCode)");
                case "periodCode" -> sql.append(" AND e.monitoring_period_code=:periodCode");
                case "transportModeCode" -> sql.append(" AND e.transport_mode_code=:transportModeCode");
                case "nodeTypeCode" -> sql.append(" AND EXISTS(SELECT 1 FROM logistics.logistics_node n WHERE n.node_code IN(e.origin_node_code,e.destination_node_code) AND n.node_type_code=:nodeTypeCode)");
                default -> throw new IllegalArgumentException();
            }
            params.put(key, value);
        });
        return new SqlFilter(sql.toString(), params);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record FieldMeta(String code, String label, String control, String binding,
                             String optionSource, String unit, Integer precision, Integer scale,
                             boolean required, int order) {}
    private record OptionRow(String field, String value, String label, int order) {}
    private record ValueRow(String id, String code, String value, String displayValue) {}
    private record Header(String id, String product, LogisticsStatus status, String reason, long version) {}
    private record SqlFilter(String sql, Map<String, Object> params) {}
}
