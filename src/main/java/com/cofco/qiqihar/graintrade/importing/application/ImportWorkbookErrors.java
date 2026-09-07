package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Safe workbook rejection messages shared by the business and sample-point imports. */
public final class ImportWorkbookErrors {
    private ImportWorkbookErrors() {}

    public static ClientRequestException invalid(IllegalArgumentException exception, String code) {
        String reason = "INVALID_XLSX";
        String message = "XLSX 模板或填写内容无效，请使用最新模板并保留原表头。";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String detail = cause.getMessage();
            if (detail != null && detail.matches("XLSX_EXTRA_COLUMN:[0-9]{1,6}")) {
                reason = detail;
                message = "文件第 " + detail.substring("XLSX_EXTRA_COLUMN:".length())
                        + " 列超出模板范围且包含内容，请移除该列内容后重试。";
                break;
            }
            if ("SAMPLE_POINT_IMPORT_TEMPLATE_MISMATCH".equals(detail)
                    || "XLSX_CONTRACT_MISMATCH".equals(detail)
                    || "XLSX_CONTEXT_MISMATCH".equals(detail)) {
                reason = detail;
                message = "文件模板与当前导入入口不匹配，请重新下载此入口的模板后填写。";
            }
        }
        LoggerFactory.getLogger(ImportWorkbookErrors.class).warn(
                "Workbook import rejected [traceId={}, code={}, reason={}]",
                MDC.get("traceId"), code, reason);
        return new ClientRequestException(code, message);
    }
}
