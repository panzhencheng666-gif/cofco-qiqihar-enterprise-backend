package com.cofco.qiqihar.graintrade.masterdata.domain;

import java.util.List;

public record PageDefinition(
        String productCode,
        String domain,
        String pageKind,
        List<FieldDefinition> fields,
        PageDefaultContext defaultContext) {

    public PageDefinition {
        fields = List.copyOf(fields);
    }
}
