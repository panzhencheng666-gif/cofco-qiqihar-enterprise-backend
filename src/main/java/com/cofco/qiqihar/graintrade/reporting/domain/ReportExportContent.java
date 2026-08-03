package com.cofco.qiqihar.graintrade.reporting.domain;

import java.util.Objects;

/** Immutable, server-produced export payload; it never accepts browser supplied report data. */
public record ReportExportContent(String id, String filename, String contentType, byte[] bytes) {
    public ReportExportContent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
