package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewView;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Produces a minimal, portable OOXML document from one immutable server-owned preview. */
public final class ReportDocument {
    private ReportDocument() {}

    public static byte[] create(ReportPreviewView preview) {
        if (preview == null) throw new IllegalArgumentException("preview");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", relationships());
                entry(zip, "word/document.xml", document(preview));
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("REPORT_DOCX_GENERATION_FAILED", exception);
        }
    }

    private static String document(ReportPreviewView preview) {
        StringBuilder body = new StringBuilder();
        paragraph(body, preview.title(), "Title");
        paragraph(body, "统计期间：" + preview.dataCutoffLabel(), "Subtitle");
        paragraph(body, "数据口径：仅采用审核通过的数据；三品种、四业务域使用同一服务端快照。", "Subtitle");
        for (ReportPreviewView.Line line : preview.lines()) {
            paragraph(body, line.label() + "：" + value(line.value())
                    + (line.note() == null || line.note().isBlank() ? "" : "（" + line.note() + "）"), "Body");
        }
        if (!preview.products().isEmpty()) {
            paragraph(body, "审核后数据快照", "Heading1");
            for (ReportPreviewView.Product product : preview.products()) {
                paragraph(body, product.label(), "Heading2");
                for (ReportPreviewView.Domain domain : product.domains()) {
                    paragraph(body, domain.label() + "  ·  已审核 " + domain.approvedRecordCount() + " 条", "Heading3");
                    paragraph(body, "数据截止：" + value(domain.dataCutoff()), "Meta");
                    for (ReportPreviewView.Line metric : domain.metrics()) {
                        paragraph(body, metric.label() + "：" + value(metric.value())
                                + (metric.note() == null || metric.note().isBlank()
                                        ? "" : "（" + metric.note() + "）"), "Body");
                    }
                }
            }
        }
        for (ReportPreviewView.Section section : preview.sections()) {
            paragraph(body, section.title(), "Heading1");
            paragraph(body, value(section.body()), "Body");
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>
                </w:document>
                """.formatted(body);
    }

    private static void paragraph(StringBuilder body, String value, String style) {
        body.append("<w:p><w:pPr>");
        switch (style) {
            case "Title" -> body.append("<w:jc w:val=\"center\"/><w:spacing w:after=\"240\"/>");
            case "Heading1" -> body.append("<w:spacing w:before=\"260\" w:after=\"120\"/><w:keepNext/>");
            case "Heading2", "Heading3" -> body.append("<w:spacing w:before=\"180\" w:after=\"80\"/><w:keepNext/>");
            default -> body.append("<w:spacing w:after=\"80\"/>");
        }
        body.append("</w:pPr><w:r><w:rPr><w:rFonts w:eastAsia=\"等线\"/>");
        switch (style) {
            case "Title" -> body.append("<w:b/><w:color w:val=\"123F35\"/><w:sz w:val=\"36\"/>");
            case "Heading1" -> body.append("<w:b/><w:color w:val=\"123F35\"/><w:sz w:val=\"28\"/>");
            case "Heading2" -> body.append("<w:b/><w:color w:val=\"A2762C\"/><w:sz w:val=\"25\"/>");
            case "Heading3" -> body.append("<w:b/><w:color w:val=\"315B52\"/><w:sz w:val=\"22\"/>");
            case "Subtitle", "Meta" -> body.append("<w:color w:val=\"687B75\"/><w:sz w:val=\"19\"/>");
            default -> body.append("<w:color w:val=\"213A34\"/><w:sz w:val=\"21\"/>");
        }
        body.append("</w:rPr>");
        body.append("<w:t xml:space=\"preserve\">").append(xml(value)).append("</w:t></w:r></w:p>");
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
    }

    private static String relationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String xml(String value) {
        return ReportDocument.value(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
