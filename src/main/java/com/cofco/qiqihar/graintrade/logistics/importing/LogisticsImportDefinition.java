package com.cofco.qiqihar.graintrade.logistics.importing;

import java.util.List;

public record LogisticsImportDefinition(String productCode, List<Field> fields) {
    public LogisticsImportDefinition { fields = List.copyOf(fields); }
    public record Field(String code, String label, String controlType, String unit,
                        Integer precision, Integer scale, boolean required, boolean readOnly) {
        public Field(String code, String label, String unit, boolean required, boolean readOnly) {
            this(code, label, inferredControl(code), unit, inferredPrecision(code), inferredScale(code),
                    required, readOnly);
        }

        private static String inferredControl(String code) {
            return decimalCode(code) ? "DECIMAL" : "TEXT";
        }

        private static Integer inferredPrecision(String code) {
            return switch (code) {
                case "surveyYear" -> 4;
                case "surveyMonth" -> 2;
                case "LOG_SAMPLE_LATITUDE" -> 9;
                case "LOG_SAMPLE_LONGITUDE" -> 10;
                case "LOG_ROUTE_VOLUME", "LOG_FREIGHT_RATE", "LOG_BOARD_PRICE" -> 18;
                default -> null;
            };
        }

        private static Integer inferredScale(String code) {
            return switch (code) {
                case "surveyYear", "surveyMonth" -> 0;
                case "LOG_SAMPLE_LATITUDE", "LOG_SAMPLE_LONGITUDE" -> 6;
                case "LOG_ROUTE_VOLUME", "LOG_FREIGHT_RATE", "LOG_BOARD_PRICE" -> 4;
                default -> null;
            };
        }

        private static boolean decimalCode(String code) {
            return inferredPrecision(code) != null;
        }
    }
}
