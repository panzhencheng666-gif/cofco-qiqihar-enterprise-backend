package com.cofco.qiqihar.graintrade.importing.domain;

import java.util.ArrayList;
import java.util.List;

/** Small RFC-4180-compatible parser for the fixed, server-owned import template. */
public final class CsvTable {
    private CsvTable() {}

    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < content.length() && content.charAt(index + 1) == '"') {
                    cell.append('"'); index++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                row.add(cell.toString()); cell.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                row.add(cell.toString()); cell.setLength(0); rows.add(List.copyOf(row)); row.clear();
            } else cell.append(current);
        }
        if (quoted) throw new IllegalArgumentException("CSV_UNTERMINATED_QUOTE");
        if (!row.isEmpty() || !cell.isEmpty()) { row.add(cell.toString()); rows.add(List.copyOf(row)); }
        return List.copyOf(rows);
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
