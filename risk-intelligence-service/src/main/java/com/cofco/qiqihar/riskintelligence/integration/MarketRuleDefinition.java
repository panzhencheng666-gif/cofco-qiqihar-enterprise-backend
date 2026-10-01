package com.cofco.qiqihar.riskintelligence.integration;

import com.cofco.qiqihar.riskintelligence.security.RiskRegionScope;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** A deliberately small, non-programmable contract for approved market price rules. */
record MarketRuleDefinition(
        String field, String operator, BigDecimal threshold, String productCode,
        String regionCode, String riskLevel, String reasonCode) {
    private static final Set<String> FIELDS = Set.of(
            "actualTradePrice", "purchaseBasePrice", "saleBasePrice");
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> KEYS = Set.of("schemaVersion", "field", "operator",
            "threshold", "productCode", "regionCode", "riskLevel", "reasonCode");
    private static final Set<String> SCOPE_KEYS = Set.of(
            "sourceRecordType", "productCode", "regionCode");

    static MarketRuleDefinition parse(JsonNode definition) {
        if (definition == null || !definition.isObject()
                || definition.path("schemaVersion").asInt(-1) != 1
                || definition.properties().stream().anyMatch(entry -> !KEYS.contains(entry.getKey()))) {
            throw new IllegalArgumentException("Unsupported market rule definition");
        }
        String field = text(definition, "field");
        String operator = text(definition, "operator");
        String riskLevel = text(definition, "riskLevel");
        String reasonCode = text(definition, "reasonCode");
        String productCode = optionalText(definition, "productCode");
        String regionCode = optionalText(definition, "regionCode");
        if (!FIELDS.contains(field) || !OPERATORS.contains(operator) || !LEVELS.contains(riskLevel)
                || !reasonCode.matches("[A-Z][A-Z0-9_]{1,79}")
                || (productCode != null && !productCode.matches("[A-Z0-9_]{1,40}"))) {
            throw new IllegalArgumentException("Invalid market rule field, scope or conclusion");
        }
        if (regionCode != null) RiskRegionScope.requireRegionCode(regionCode);
        BigDecimal threshold;
        try {
            JsonNode value = definition.path("threshold");
            if (!value.isTextual()) throw new IllegalArgumentException("Price threshold must be decimal text");
            threshold = new BigDecimal(value.asText());
            if (threshold.signum() <= 0 || threshold.scale() > 4 || threshold.precision() > 18) {
                throw new IllegalArgumentException("Price threshold is outside the market price range");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Price threshold must be decimal text", exception);
        }
        return new MarketRuleDefinition(field, operator, threshold,
                productCode, regionCode, riskLevel, reasonCode);
    }

    void requireMatchingScope(JsonNode scope) {
        if (scope == null || !scope.isObject()
                || scope.properties().stream().anyMatch(entry -> !SCOPE_KEYS.contains(entry.getKey()))
                || !"MARKET_RECORD".equals(scope.path("sourceRecordType").asText(null))
                || !java.util.Objects.equals(productCode, optionalText(scope, "productCode"))
                || !java.util.Objects.equals(regionCode, optionalText(scope, "regionCode"))) {
            throw new IllegalArgumentException("Market rule scope differs from its approved definition");
        }
    }

    boolean matches(Map<String, Object> payload) {
        if (!"APPROVED".equals(payload.get("statusCode"))) return false;
        if (productCode != null && !productCode.equals(payload.get("productCode"))) return false;
        if (regionCode != null && !regionCode.equals(payload.get("regionCode"))) return false;
        Object observed = payload.get(field);
        if (!(observed instanceof String value)) return false;
        BigDecimal price;
        try { price = new BigDecimal(value); }
        catch (NumberFormatException exception) { return false; }
        int comparison = price.compareTo(threshold);
        return switch (operator) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            default -> false;
        };
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value.path(field);
        if (!item.isTextual() || item.asText().isBlank()) {
            throw new IllegalArgumentException("Market rule field is required: " + field);
        }
        return item.asText();
    }

    private static String optionalText(JsonNode value, String field) {
        JsonNode item = value.path(field);
        if (item.isMissingNode() || item.isNull()) return null;
        if (!item.isTextual() || item.asText().isBlank()) {
            throw new IllegalArgumentException("Invalid market rule scope: " + field);
        }
        return item.asText();
    }
}
