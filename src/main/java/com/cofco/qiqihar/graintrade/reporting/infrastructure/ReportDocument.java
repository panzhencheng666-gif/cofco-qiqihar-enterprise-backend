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
        paragraph(body, preview.title(), true);
        paragraph(body, "统计期间：" + preview.dataCutoffLabel(), false);
        for (ReportPreviewView.Line line : preview.lines()) {
            paragraph(body, line.label() + "：" + value(line.value())
                    + (line.note() == null || line.note().isBlank() ? "" : "（" + line.note() + "）"), false);
        }
        for (ReportPreviewView.Section section : preview.sections()) {
            paragraph(body, section.title(), true);
            paragraph(body, value(section.body()), false);
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body>
                </w:document>
                """.formatted(body);
    }

    private static void paragraph(StringBuilder body, String value, boolean bold) {
        body.append("<w:p><w:r>");
        if (bold) body.append("<w:rPr><w:b/></w:rPr>");
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
