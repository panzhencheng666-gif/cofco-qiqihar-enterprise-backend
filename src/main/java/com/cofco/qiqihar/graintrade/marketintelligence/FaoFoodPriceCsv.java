package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Parses only the seven published columns of FAO's monthly nominal-index CSV. */
public final class FaoFoodPriceCsv {
    private FaoFoodPriceCsv() { }

    public record Observation(FaoFoodPriceSeries series, LocalDate period, BigDecimal value) { }
    public record Parsed(List<Observation> observations, LocalDate latestPeriod) { }

    public static Parsed parse(byte[] bytes, LocalDate today) throws IOException {
        var body = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        var lines = body.split("\\R");
        if (lines.length < 25 || !lines[0].startsWith("FAO Food Price Index")
                || !lines[1].startsWith("2014-2016=100"))
            throw new IOException("Unexpected FAO food-price CSV title");
        var header = lines[2].split(",", -1);
        var expected = new String[] { "Date", "Food Price Index", "Meat", "Dairy", "Cereals", "Oils", "Sugar" };
        if (header.length < expected.length) throw new IOException("Incomplete FAO food-price header");
        for (int column = 0; column < expected.length; column++)
            if (!expected[column].equals(header[column].trim()))
                throw new IOException("Changed FAO food-price header");

        var observations = new ArrayList<Observation>();
        YearMonth previous = null;
        for (int row = 4; row < lines.length; row++) {
            if (lines[row].isBlank()) continue;
            var columns = lines[row].split(",", -1);
            if (columns.length < expected.length) throw new IOException("Incomplete FAO month");
            YearMonth month;
            try { month = YearMonth.parse(columns[0].trim()); }
            catch (RuntimeException invalid) { throw new IOException("Invalid FAO month", invalid); }
            if (previous != null && !month.equals(previous.plusMonths(1)))
                throw new IOException("Gap or duplicate in FAO monthly series");
            if (month.isAfter(YearMonth.from(today)))
                throw new IOException("FAO month is in the future");
            for (int column = 1; column < expected.length; column++) {
                BigDecimal value;
                try { value = new BigDecimal(columns[column].trim()); }
                catch (RuntimeException invalid) { throw new IOException("Invalid FAO index value", invalid); }
                if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(1000)) > 0)
                    throw new IOException("Out-of-range FAO index value");
                observations.add(new Observation(FaoFoodPriceSeries.values()[column - 1],
                        month.atDay(1), value));
            }
            previous = month;
        }
        if (previous == null || observations.size() < 24 * FaoFoodPriceSeries.values().length)
            throw new IOException("FAO food-price history is incomplete");
        return new Parsed(List.copyOf(observations), previous.atDay(1));
    }
}
