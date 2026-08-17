package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Bounded OOXML worksheet reader. It intentionally never evaluates formulas. */
public final class XlsxTable {
    private static final int MAX_EXPANDED_BYTES = 80 * 1024 * 1024;
    private static final int MAX_ROWS = 5_002;
    private static final int MAX_CELL_CODE_POINTS = 500;

    private XlsxTable() {}

    public static List<List<String>> parse(byte[] bytes, int expectedColumns) {
        return parseWorksheet(bytes, 1, expectedColumns);
    }

    public static List<List<String>> parseWorksheet(byte[] bytes, int worksheetNumber, int expectedColumns) {
        return parseWorksheet(bytes, worksheetNumber, expectedColumns, MAX_ROWS);
    }

    public static List<List<String>> parseWorksheet(
            byte[] bytes, int worksheetNumber, int expectedColumns, int maxRows) {
        if (bytes == null || bytes.length == 0 || expectedColumns < 1) throw invalid();
        if (worksheetNumber < 1 || maxRows < 1 || maxRows > 50_002) throw invalid();
        Map<String, byte[]> entries = unzip(bytes);
        if (entries.keySet().stream().anyMatch(name -> name.contains("vbaProject")
                || name.startsWith("xl/externalLinks/"))) throw invalid();
        List<String> sharedStrings = entries.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(entries.get("xl/sharedStrings.xml")) : List.of();
        byte[] sheet = entries.get("xl/worksheets/sheet" + worksheetNumber + ".xml");
        if (sheet == null) throw invalid();
        return rows(sheet, sharedStrings, expectedColumns, maxRows);
    }

    static Map<String, String> parseDefinedNames(byte[] bytes, List<String> expectedNames) {
        if (bytes == null || bytes.length == 0 || expectedNames == null
                || expectedNames.isEmpty() || Set.copyOf(expectedNames).size() != expectedNames.size()) {
            throw invalid();
        }
        Map<String, byte[]> entries = unzip(bytes);
        if (entries.keySet().stream().anyMatch(name -> name.contains("vbaProject")
                || name.startsWith("xl/externalLinks/"))) throw invalid();
        byte[] workbook = entries.get("xl/workbook.xml");
        if (workbook == null) throw invalid();
        var document = document(workbook);
        Map<String, String> values = new HashMap<>();
        var names = document.getElementsByTagNameNS("*", "definedName");
        for (int index = 0; index < names.getLength(); index++) {
            Element name = (Element) names.item(index);
            String key = name.getAttribute("name");
            if (!expectedNames.contains(key)) continue;
            String hidden = name.getAttribute("hidden");
            if (!("1".equals(hidden) || "true".equalsIgnoreCase(hidden)) || values.containsKey(key)
                    || name.getElementsByTagNameNS("*", "*").getLength() > 0) throw invalid();
            String value = definedNameString(name.getTextContent().trim());
            if (value.codePointCount(0, value.length()) > MAX_CELL_CODE_POINTS) throw invalid();
            values.put(key, value);
        }
        if (!values.keySet().equals(Set.copyOf(expectedNames))) throw invalid();
        return Map.copyOf(values);
    }

    private static String definedNameString(String formula) {
        if (formula.length() < 2 || formula.charAt(0) != '"'
                || formula.charAt(formula.length() - 1) != '"') throw invalid();
        return formula.substring(1, formula.length() - 1).replace("\"\"", "\"");
    }

    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw invalid();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = zip.read(buffer)) >= 0;) {
                    if (count == 0) continue;
                    total = Math.addExact(total, count);
                    if (total > MAX_EXPANDED_BYTES) throw invalid();
                    output.write(buffer, 0, count);
                }
                if (entries.put(name, output.toByteArray()) != null) throw invalid();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
        return entries;
    }

    private static List<String> sharedStrings(byte[] xml) {
        var document = document(xml);
        var nodes = document.getElementsByTagNameNS("*", "si");
        List<String> values = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) values.add(nodes.item(index).getTextContent());
        return List.copyOf(values);
    }

    private static List<List<String>> rows(
            byte[] xml, List<String> sharedStrings, int expectedColumns, int maxRows) {
        var document = document(xml);
        if (document.getElementsByTagNameNS("*", "f").getLength() > 0) throw invalid();
        var rowNodes = document.getElementsByTagNameNS("*", "row");
        if (rowNodes.getLength() > maxRows) throw invalid();
        List<List<String>> rows = new ArrayList<>(rowNodes.getLength());
        for (int rowIndex = 0; rowIndex < rowNodes.getLength(); rowIndex++) {
            List<String> values = new ArrayList<>(java.util.Collections.nCopies(expectedColumns, ""));
            var cells = ((Element) rowNodes.item(rowIndex)).getElementsByTagNameNS("*", "c");
            for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
                Element cell = (Element) cells.item(cellIndex);
                int column = column(cell.getAttribute("r"));
                if (column < 0 || column >= expectedColumns) throw invalid();
                String value = cellValue(cell, sharedStrings);
                if (value.codePointCount(0, value.length()) > MAX_CELL_CODE_POINTS) throw invalid();
                values.set(column, value);
            }
            rows.add(List.copyOf(values));
        }
        return List.copyOf(rows);
    }

    private static String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if (type.equals("inlineStr")) return text(cell, "t");
        String raw = text(cell, "v");
        if (type.equals("s")) {
            try {
                int index = Integer.parseInt(raw);
                if (index < 0 || index >= sharedStrings.size()) throw invalid();
                return sharedStrings.get(index);
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }
        if (type.isEmpty() || type.equals("n") || type.equals("str")) return raw;
        throw invalid();
    }

    private static String text(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static int column(String reference) {
        if (reference == null || reference.isEmpty()) throw invalid();
        int value = 0;
        int index = 0;
        while (index < reference.length() && Character.isLetter(reference.charAt(index))) {
            char letter = Character.toUpperCase(reference.charAt(index++));
            value = Math.addExact(Math.multiplyExact(value, 26), letter - 'A' + 1);
        }
        if (index == 0 || index == reference.length()) throw invalid();
        return value - 1;
    }

    private static org.w3c.dom.Document document(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_XLSX");
    }
}
