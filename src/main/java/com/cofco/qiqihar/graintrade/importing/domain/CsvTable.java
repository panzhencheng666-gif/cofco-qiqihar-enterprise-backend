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
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < content.length() && content.charAt(index + 1) == '"') {
                    append(cell, '"'); index++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                addCell(row, cell);
                if (expectedColumns > 0 && row.size() >= expectedColumns) throw columnLimit();
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                addCell(row, cell);
                addRow(rows, row, expectedColumns);
            } else append(cell, current);
        }
        if (quoted) throw new IllegalArgumentException("CSV_UNTERMINATED_QUOTE");
        if (!row.isEmpty() || !cell.isEmpty()) {
            addCell(row, cell);
            addRow(rows, row, expectedColumns);
        }
        return List.copyOf(rows);
    }

    private static void append(StringBuilder cell, char value) {
        if (cell.length() >= MAX_CELL_CHARACTERS) {
            throw new LimitExceededException("IMPORT_CELL_LIMIT_EXCEEDED", "CSV cell exceeds 500 characters");
        }
        cell.append(value);
    }

    private static void addCell(List<String> row, StringBuilder cell) {
        row.add(cell.toString());
        cell.setLength(0);
    }

    private static void addRow(List<List<String>> rows, List<String> row, int expectedColumns) {
        if (expectedColumns > 0 && row.size() != expectedColumns) throw columnLimit();
        if (rows.size() >= MAX_ROWS + 1) {
            throw new LimitExceededException("IMPORT_ROW_LIMIT_EXCEEDED", "CSV exceeds 5000 data rows");
        }
        rows.add(List.copyOf(row));
        row.clear();
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
