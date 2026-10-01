package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarketRuleDefinitionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyApprovedMatchingProductAndRegionCanTriggerAConfiguredThreshold() {
        var rule = MarketRuleDefinition.parse(json.readTree("""
                {"schemaVersion":1,"field":"actualTradePrice","operator":"GT",
                 "threshold":"3000.0000","productCode":"CORN","regionCode":"230221",
                 "riskLevel":"LOW","reasonCode":"PRICE_ABOVE_CONFIGURED_LIMIT"}
                """));
        var fact = Map.<String, Object>of("statusCode", "APPROVED", "productCode", "CORN",
                "regionCode", "230221", "actualTradePrice", "3000.0001");
        rule.requireMatchingScope(json.readTree("""
                {"sourceRecordType":"MARKET_RECORD","productCode":"CORN","regionCode":"230221"}
                """));
        assertThatThrownBy(() -> rule.requireMatchingScope(json.readTree("""
                {"sourceRecordType":"MARKET_RECORD","productCode":"CORN"}
                """))).isInstanceOf(IllegalArgumentException.class);
        assertThat(rule.matches(fact)).isTrue();
        assertThat(rule.matches(Map.of("statusCode", "DRAFT", "productCode", "CORN",
                "regionCode", "230221", "actualTradePrice", "9999"))).isFalse();
        assertThat(rule.matches(Map.of("statusCode", "APPROVED", "productCode", "SOYBEAN",
                "regionCode", "230221", "actualTradePrice", "9999"))).isFalse();
        assertThat(rule.matches(Map.of("statusCode", "APPROVED", "productCode", "CORN",
                "regionCode", "230221", "actualTradePrice", "3000.0000"))).isFalse();
    }

    @Test
    void missingPriceOrUnknownCodeCannotCreateAnAssessment() {
        var rule = MarketRuleDefinition.parse(json.readTree("""
                {"schemaVersion":1,"field":"actualTradePrice","operator":"LT",
                 "threshold":"2000","riskLevel":"MEDIUM","reasonCode":"PRICE_BELOW_LIMIT"}
                """));
        assertThat(rule.matches(Map.of("statusCode", "APPROVED"))).isFalse();
        assertThat(rule.matches(Map.of("statusCode", "APPROVED", "actualTradePrice", "not-a-price")))
                .isFalse();
        assertThatThrownBy(() -> MarketRuleDefinition.parse(json.readTree("""
                {"schemaVersion":1,"field":"unexpected","operator":"LT","threshold":"2000",
                 "riskLevel":"MEDIUM","reasonCode":"PRICE_BELOW_LIMIT"}
                """))).isInstanceOf(IllegalArgumentException.class);
    }
}
