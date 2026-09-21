package com.cofco.qiqihar.riskintelligence.integration;

record SourceFactKey(
        String sourceSystem,
        String sourceRecordType,
        String sourceRecordId,
        String sourceVersion) {

    String displayName() {
        return String.join("/", sourceSystem, sourceRecordType, sourceRecordId, sourceVersion);
    }
}
