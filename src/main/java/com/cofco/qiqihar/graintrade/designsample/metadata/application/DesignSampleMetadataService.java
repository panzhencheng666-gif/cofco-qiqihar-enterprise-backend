package com.cofco.qiqihar.graintrade.designsample.metadata.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleValidationResult.ValueState;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleFieldDefinition;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DesignSampleMetadataService {
    private final DesignSampleMetadataCatalog catalog;
    private final ObjectMapper json;

    public DesignSampleMetadataService(
            DesignSampleMetadataCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    public DesignSampleMetadataDefinition definition(DesignSampleContext context) {
        DesignSampleContractSnapshot snapshot = catalog.loadActiveContract();
        List<DesignSampleFieldDefinition> fields = fields(snapshot, context);
        return new DesignSampleMetadataDefinition(
                snapshot.contractVersion(),
                snapshot.contractDigest(),
                context,
                snapshot.domains(),
                snapshot.products(),
                snapshot.objectTypes(),
                snapshot.supportedContexts(),
                fields.stream().filter(field -> field.sectionCode().equals("IDENTITY")).toList(),
                fields.stream().filter(field -> field.sectionCode().equals("OBSERVATION")).toList());
    }

    public DesignSampleContractSnapshot activeContract() {
        return catalog.loadActiveContract();
    }

    public DesignSampleValidationResult validate(
            String contractVersion,
            String contractDigest,
            DesignSampleContext context,
            Map<String, JsonNode> values) {
        return validateForPersistence(
                contractVersion, contractDigest, context, values).publicResult();
    }

    public ValidatedDesignSampleValues validateForPersistence(
            String contractVersion,
            String contractDigest,
            DesignSampleContext context,
            Map<String, JsonNode> values) {
        DesignSampleContractSnapshot snapshot = catalog.loadActiveContract();
        List<DesignSampleFieldDefinition> applicable = fields(snapshot, context);
        if (!snapshot.contractVersion().equals(contractVersion)
                || !snapshot.contractDigest().equals(contractDigest)) {
            throw error("CONTRACT_MISMATCH", "设计样本点字段合同版本或摘要不匹配");
        }
        if (values == null) throw error("FIELD_VALUE_INVALID", "字段值不能为空");

        Map<String, DesignSampleFieldDefinition> applicableByCode = new LinkedHashMap<>();
        applicable.forEach(field -> applicableByCode.put(field.code(), field));
        Map<String, ValueState> states = new LinkedHashMap<>();
        Map<String, JsonNode> normalizedValues = new LinkedHashMap<>();
        Map<String, BigDecimal> decimalValues = new LinkedHashMap<>();
        boolean knownBusinessObservation = false;

        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            String code = entry.getKey();
            if (!snapshot.fieldsByCode().containsKey(code)) {
                throw error("UNKNOWN_FIELD_CODE", "存在未定义的设计样本点字段");
            }
            DesignSampleFieldDefinition field = applicableByCode.get(code);
            if (field == null) {
                throw error("FIELD_NOT_APPLICABLE", "字段不适用于当前设计样本点上下文");
            }
            if (!field.editable()) {
                throw error("READ_ONLY_FIELD", "客户端不能填写只读设计样本点字段");
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                if (!field.nullable()) {
                    throw error("FIELD_VALUE_INVALID", "不可空字段不能使用未知值");
                }
                states.put(code, ValueState.UNKNOWN);
                normalizedValues.put(code, json.getNodeFactory().nullNode());
                continue;
            }
            JsonNode normalized = normalizeKnown(field, value);
            normalizedValues.put(code, normalized);
            if (field.valueType().equals("DECIMAL")) {
                decimalValues.put(code, normalized.decimalValue());
            }
            states.put(code, ValueState.KNOWN);
            if (field.sectionCode().equals("OBSERVATION") && !field.code().equals("OBSERVED_ON")) {
                knownBusinessObservation = true;
            }
        }

        boolean missingRequired = applicable.stream()
                .filter(DesignSampleFieldDefinition::editable)
                .filter(DesignSampleFieldDefinition::required)
                .filter(field -> field.defaultValue() == null)
                .anyMatch(field -> !states.containsKey(field.code()));
        if (missingRequired) {
            throw error("REQUIRED_FIELD_MISSING", "缺少必填的设计样本点字段");
        }

        validateProductionAreaRelationships(decimalValues);
        if (!context.equals(new DesignSampleContext("REFERENCE", "GENERAL", "REFERENCE_POINT"))
                && !knownBusinessObservation) {
            throw error("EMPTY_OBSERVATION", "至少需要一个已知的适用业务观测值");
        }
        return new ValidatedDesignSampleValues(
                snapshot.contractVersion(), snapshot.contractDigest(), context,
                normalizedValues, states);
    }

    private List<DesignSampleFieldDefinition> fields(
            DesignSampleContractSnapshot snapshot,
            DesignSampleContext context) {
        List<DesignSampleFieldDefinition> fields = snapshot.fieldsByContext().get(context);
        if (fields == null) {
            throw error("INVALID_DESIGN_SAMPLE_CONTEXT", "设计样本点业务域、产品和对象组合不合法");
        }
        return fields;
    }

    private JsonNode normalizeKnown(DesignSampleFieldDefinition field, JsonNode value) {
        return switch (field.valueType()) {
            case "UUID" -> {
                if (!value.isTextual()) invalidValue();
                try {
                    yield json.getNodeFactory().textNode(UUID.fromString(value.asText()).toString());
                } catch (IllegalArgumentException exception) {
                    yield invalidValue();
                }
            }
            case "DATE" -> {
                if (!value.isTextual() || !validDate(value.asText())) invalidValue();
                yield json.getNodeFactory().textNode(value.asText());
            }
            case "STRING" -> {
                if (!value.isTextual() || value.asText().isBlank()
                        || (field.maxLength() != null && value.asText().length() > field.maxLength())) {
                    invalidValue();
                }
                yield json.getNodeFactory().textNode(value.asText().trim());
            }
            case "ENUM" -> {
                if (!value.isTextual() || !field.enumOptions().contains(value.asText())) invalidValue();
                yield json.getNodeFactory().textNode(value.asText());
            }
            case "DECIMAL" -> json.getNodeFactory().numberNode(
                    decimal(field, value).stripTrailingZeros());
            default -> throw error("FIELD_VALUE_INVALID", "字段类型不受支持");
        };
    }

    private BigDecimal decimal(DesignSampleFieldDefinition field, JsonNode value) {
        BigDecimal decimal;
        try {
            if (value.isNumber()) {
                decimal = value.decimalValue();
            } else if (value.isTextual()) {
                decimal = new BigDecimal(value.asText());
            } else {
                return invalidValue();
            }
        } catch (NumberFormatException exception) {
            return invalidValue();
        }
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (field.precision() != null && field.scale() != null) {
            int fractionalDigits = Math.max(normalized.scale(), 0);
            int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
            if (fractionalDigits > field.scale()
                    || integerDigits > field.precision() - field.scale()) {
                invalidValue();
            }
        }
        if (field.minimumValue() != null
                && decimal.compareTo(new BigDecimal(field.minimumValue())) < 0) invalidValue();
        if (field.maximumValue() != null
                && decimal.compareTo(new BigDecimal(field.maximumValue())) > 0) invalidValue();
        return decimal;
    }

    private void validateProductionAreaRelationships(Map<String, BigDecimal> values) {
        BigDecimal area = values.get("PROD_AREA_MU");
        if (area == null) return;
        if (greaterThan(values.get("PROD_HARVEST_AREA_MU"), area)
                || greaterThan(values.get("PROD_AFFECTED_AREA_MU"), area)) {
            throw error("FIELD_VALUE_INVALID", "收获面积或灾损面积不能超过播种面积");
        }
    }

    private static boolean greaterThan(BigDecimal value, BigDecimal limit) {
        return value != null && value.compareTo(limit) > 0;
    }

    private static boolean validDate(String value) {
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static <T> T invalidValue() {
        throw error("FIELD_VALUE_INVALID", "设计样本点字段值不符合类型、精度、枚举或范围合同");
    }

    private static ClientRequestException error(String code, String message) {
        return new ClientRequestException(code, message);
    }
}
