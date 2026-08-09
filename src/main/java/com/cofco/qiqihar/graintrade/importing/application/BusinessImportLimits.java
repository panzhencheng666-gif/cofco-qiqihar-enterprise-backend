package com.cofco.qiqihar.graintrade.importing.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class BusinessImportLimits {
    private final int synchronousRows;
    private final int maximumRows;
    private final int maximumBytes;

    public BusinessImportLimits(
            @Value("${qiqihar.import.sync-row-limit:5000}") int synchronousRows,
            @Value("${qiqihar.import.max-row-limit:50000}") int maximumRows,
            @Value("${qiqihar.import.max-file-bytes:20971520}") int maximumBytes) {
        if (synchronousRows < 1 || maximumRows <= synchronousRows || maximumRows > 50_000
                || maximumBytes < 1 || maximumBytes > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("Import limits are invalid");
        }
        this.synchronousRows = synchronousRows;
        this.maximumRows = maximumRows;
        this.maximumBytes = maximumBytes;
    }

    public int synchronousRows() { return synchronousRows; }
    public int maximumRows() { return maximumRows; }
    public int maximumBytes() { return maximumBytes; }

    public boolean queued(int dataRows) { return dataRows > synchronousRows; }
}
