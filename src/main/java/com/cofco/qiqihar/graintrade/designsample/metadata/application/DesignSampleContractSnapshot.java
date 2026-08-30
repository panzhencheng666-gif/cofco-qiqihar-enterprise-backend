package com.cofco.qiqihar.graintrade.designsample.metadata.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleFieldDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DesignSampleContractSnapshot(
        String contractVersion,
        String contractDigest,
        List<DomainDefinition> domains,
        List<ProductDefinition> products,
        List<ObjectTypeDefinition> objectTypes,
        List<SupportedContext> supportedContexts,
        Map<DesignSampleContext, List<DesignSampleFieldDefinition>> fieldsByContext,
        Map<String, DesignSampleFieldDefinition> fieldsByCode) {
    public DesignSampleContractSnapshot {
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        Objects.requireNonNull(contractDigest, "contractDigest must not be null");
        domains = List.copyOf(domains);
        products = List.copyOf(products);
        objectTypes = List.copyOf(objectTypes);
        supportedContexts = List.copyOf(supportedContexts);
        fieldsByContext = immutableListMap(fieldsByContext);
        fieldsByCode = Map.copyOf(fieldsByCode);
    }

    private static Map<DesignSampleContext, List<DesignSampleFieldDefinition>> immutableListMap(
            Map<DesignSampleContext, List<DesignSampleFieldDefinition>> source) {
        Map<DesignSampleContext, List<DesignSampleFieldDefinition>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    public record DomainDefinition(
            String code,
            String label,
            String description,
            List<String> aliases,
            int sortOrder) {
        public DomainDefinition {
            aliases = List.copyOf(aliases);
        }
    }

    public record ProductDefinition(
            String code,
            String label,
            List<String> aliases,
            int sortOrder) {
        public ProductDefinition {
            aliases = List.copyOf(aliases);
        }
    }

    public record ObjectTypeDefinition(
            String domainCode,
            String code,
            String label,
            List<String> aliases,
            int sortOrder) {
        public ObjectTypeDefinition {
            aliases = List.copyOf(aliases);
        }
    }

    public record SupportedContext(
            String domainCode,
            String productCode,
            String objectTypeCode,
            int sortOrder) {
        public DesignSampleContext key() {
            return new DesignSampleContext(domainCode, productCode, objectTypeCode);
        }
    }
}
