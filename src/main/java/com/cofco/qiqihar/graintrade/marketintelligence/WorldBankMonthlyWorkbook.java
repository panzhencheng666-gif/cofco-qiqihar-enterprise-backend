package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Parses only the named monthly-price worksheet and its declared source units. */
public final class WorldBankMonthlyWorkbook {
    private static final Pattern PERIOD = Pattern.compile("[0-9]{4}M(0[1-9]|1[0-2])");
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH);
    private WorldBankMonthlyWorkbook() { }

    public record Observation(WorldBankMonthlySeries series, LocalDate period, BigDecimal price) { }
    public record Parsed(LocalDate sourceUpdatedOn, List<Observation> observations) { }

    public static Parsed parse(byte[] bytes) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("Monthly Prices");
            if (sheet == null || !"World Bank Commodity Price Data (The Pink Sheet)".equals(text(sheet.getRow(0), 0))) {
                throw new IOException("World Bank monthly-price worksheet missing or changed");
            }
            var updated = text(sheet.getRow(3), 0);
            if (!updated.startsWith("Updated on ")) throw new IOException("Source update date missing");
            LocalDate sourceUpdatedOn;
            try { sourceUpdatedOn = LocalDate.parse(updated.substring(11), UPDATED); }
            catch (RuntimeException invalid) { throw new IOException("Invalid source update date", invalid); }
            if (sourceUpdatedOn.isAfter(LocalDate.now())) throw new IOException("Future source update date");
            var columns = new EnumMap<WorldBankMonthlySeries, Integer>(WorldBankMonthlySeries.class);
            Map<String, Integer> names = new HashMap<>();
            var heading = sheet.getRow(4);
            for (Cell cell : heading) names.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
            for (var series : WorldBankMonthlySeries.values()) {
                var index = names.get(series.sourceHeader);
                if (index == null || !series.sourceUnit.equals(text(sheet.getRow(5), index))) {
                    throw new IOException("Source series or unit changed: " + series.code);
                }
                columns.put(series, index);
            }
            var observations = new ArrayList<Observation>();
            for (int rowIndex = 6; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                var periodText = text(row, 0);
                if (!PERIOD.matcher(periodText).matches()) continue;
                var period = LocalDate.of(Integer.parseInt(periodText.substring(0, 4)),
                        Integer.parseInt(periodText.substring(5)), 1);
                if (period.isAfter(LocalDate.now().withDayOfMonth(1))) throw new IOException("Future source period");
                for (var series : WorldBankMonthlySeries.values()) {
                    var cell = row.getCell(columns.get(series));
                    if (cell == null || cell.getCellType() != CellType.NUMERIC) continue;
                    var value = BigDecimal.valueOf(cell.getNumericCellValue());
                    if (value.signum() > 0) observations.add(new Observation(series, period, value));
                }
            }
            for (var series : WorldBankMonthlySeries.values()) {
                long count = observations.stream().filter(value -> value.series() == series).count();
                if (count < 12) throw new IOException("Insufficient monthly history: " + series.code);
            }
            return new Parsed(sourceUpdatedOn, List.copyOf(observations));
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("World Bank workbook format changed", e);
        }
    }

    private static String text(Row row, int column) {
        if (row == null) return "";
        var cell = row.getCell(column);
        return cell == null ? "" : new DataFormatter(Locale.ENGLISH).formatCellValue(cell).trim();
    }
}
