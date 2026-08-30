package com.cofco.qiqihar.graintrade.designsample.metadata.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleFieldDefinition;
import java.util.List;

public record DesignSampleMetadataDefinition(
        String contractVersion,
        String contractDigest,
        DesignSampleContext context,
        List<DesignSampleContractSnapshot.DomainDefinition> domains,
        List<DesignSampleContractSnapshot.ProductDefinition> products,
        List<DesignSampleContractSnapshot.ObjectTypeDefinition> objectTypes,
        List<DesignSampleContractSnapshot.SupportedContext> supportedContexts,
        List<DesignSampleFieldDefinition> identityFields,
        List<DesignSampleFieldDefinition> observationFields) {
    public DesignSampleMetadataDefinition {
        domains = List.copyOf(domains);
        products = List.copyOf(products);
        objectTypes = List.copyOf(objectTypes);
        supportedContexts = List.copyOf(supportedContexts);
        identityFields = List.copyOf(identityFields);
        observationFields = List.copyOf(observationFields);
    }
}
