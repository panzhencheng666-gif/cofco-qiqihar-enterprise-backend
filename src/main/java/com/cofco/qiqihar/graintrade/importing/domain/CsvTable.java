package com.cofco.qiqihar.graintrade.importing.domain;

import java.util.ArrayList;
import java.util.List;

/** Small RFC-4180-compatible parser for the fixed, server-owned import template. */
public final class CsvTable {
    public static final int MAX_ROWS = 5_000;
    public static final int MAX_CELL_CHARACTERS = 500;

    private CsvTable() {}

    public static List<List<String>> parse(String content) {
        return parse(content, 0);
    }

    public static List<List<String>> parse(String content, int expectedColumns) {
        return parse(content, expectedColumns, MAX_ROWS);
    }

    public static List<List<String>> parse(String content, int expectedColumns, int maxDataRows) {
        if (content == null || maxDataRows < 1 || maxDataRows > 50_000) {
            throw new IllegalArgumentException("INVALID_CSV_LIMIT");
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int cellCharacters = 0;
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            if (!quoted && row.isEmpty() && cell.isEmpty() && rows.size() >= maxDataRows + 1) {
                throw rowLimit(maxDataRows);
            }
            char current = content.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < content.length() && content.charAt(index + 1) == '"') {
                    cellCharacters = append(cell, '"', cellCharacters); index++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                addCell(row, cell);
                cellCharacters = 0;
                if (expectedColumns > 0 && row.size() >= expectedColumns) throw columnLimit();
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                addCell(row, cell);
                cellCharacters = 0;
                addRow(rows, row, expectedColumns, maxDataRows);
            } else {
                int codePoint = content.codePointAt(index);
                cellCharacters = append(cell, codePoint, cellCharacters);
                index += Character.charCount(codePoint) - 1;
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV_UNTERMINATED_QUOTE");
        if (!row.isEmpty() || !cell.isEmpty()) {
            addCell(row, cell);
            addRow(rows, row, expectedColumns, maxDataRows);
        }
        return List.copyOf(rows);
    }

    private static int append(StringBuilder cell, int codePoint, int cellCharacters) {
        if (cellCharacters >= MAX_CELL_CHARACTERS) {
            throw new LimitExceededException("IMPORT_CELL_LIMIT_EXCEEDED", "CSV cell exceeds 500 characters");
        }
        cell.appendCodePoint(codePoint);
        return cellCharacters + 1;
    }

    private static void addCell(List<String> row, StringBuilder cell) {
        row.add(cell.toString());
        cell.setLength(0);
    }

    private static void addRow(List<List<String>> rows, List<String> row, int expectedColumns, int maxDataRows) {
        if (expectedColumns > 0 && row.size() != expectedColumns) throw columnLimit();
        if (rows.size() >= maxDataRows + 1) throw rowLimit(maxDataRows);
        rows.add(List.copyOf(row));
        row.clear();
    }

    private static LimitExceededException rowLimit(int maxDataRows) {
        return new LimitExceededException("IMPORT_ROW_LIMIT_EXCEEDED",
                "CSV exceeds " + maxDataRows + " data rows");
    }

    private static LimitExceededException columnLimit() {
        return new LimitExceededException("IMPORT_COLUMN_COUNT_EXCEEDED",
                "CSV row column count does not match the template");
    }

    public static final class LimitExceededException extends IllegalArgumentException {
        private final String code;

        private LimitExceededException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
