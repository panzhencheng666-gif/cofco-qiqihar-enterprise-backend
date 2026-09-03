package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.util.List;
import java.util.Map;

/**
 * Compatibility facade for import code. The bounded OOXML reader is shared by import and
 * sample-point governance without creating a module cycle.
 */
public final class XlsxTable {
    private XlsxTable() {}

    public static List<List<String>> parse(byte[] bytes, int expectedColumns) {
        return com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable.parse(
                bytes, expectedColumns);
    }

    public static List<List<String>> parseWorksheet(
            byte[] bytes, int worksheetNumber, int expectedColumns) {
        return com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable.parseWorksheet(
                bytes, worksheetNumber, expectedColumns);
    }

    public static List<String> parseWorksheetNames(byte[] bytes) {
        return com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable.parseWorksheetNames(bytes);
    }

    public static List<List<String>> parseWorksheet(
            byte[] bytes, int worksheetNumber, int expectedColumns, int maxRows) {
        return com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable.parseWorksheet(
                bytes, worksheetNumber, expectedColumns, maxRows);
    }

    static Map<String, String> parseDefinedNames(byte[] bytes, List<String> expectedNames) {
        return com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable.parseDefinedNames(
                bytes, expectedNames);
    }
}
