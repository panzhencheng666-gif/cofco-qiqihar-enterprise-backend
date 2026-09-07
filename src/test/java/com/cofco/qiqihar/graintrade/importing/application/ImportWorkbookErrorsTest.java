package com.cofco.qiqihar.graintrade.importing.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ImportWorkbookErrorsTest {
    @Test
    void explainsWrappedExtraColumnsWithoutExposingInternalExceptions() {
        var exception = new IllegalArgumentException("INVALID_SAMPLE_POINT_IMPORT_FORMAT",
                new IllegalArgumentException("XLSX_EXTRA_COLUMN:9"));
        var error = ImportWorkbookErrors.invalid(exception, "INVALID_SAMPLE_POINT_IMPORT_FORMAT");
        assertThat(error.code()).isEqualTo("INVALID_SAMPLE_POINT_IMPORT_FORMAT");
        assertThat(error.clientMessage()).isEqualTo("文件第 9 列超出模板范围且包含内容，请移除该列内容后重试。");
        assertThat(ImportWorkbookErrors.invalid(new IllegalArgumentException("secret database detail"),
                "INVALID_IMPORT_FORMAT").clientMessage()).doesNotContain("secret");
    }
}
