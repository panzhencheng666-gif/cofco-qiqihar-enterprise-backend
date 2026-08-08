package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Collection;
import java.util.Map;

/** Shared protocol-boundary limits for business requests. */
public final class BoundedInput {
    public static final int MAX_AGGREGATE_ENTRIES = 256;
    public static final int MAX_TEXT_CODE_POINTS = 500;

    private BoundedInput() {
    }

    public static void requireAggregateSize(String code, Collection<?>... collections) {
        int total = 0;
        for (Collection<?> collection : collections) {
            if (collection != null) {
                if (collection.size() > MAX_AGGREGATE_ENTRIES - total) throw invalid(code);
                total += collection.size();
            }
        }
    }

    @SafeVarargs
    public static void requireAggregateSize(String code, Map<?, ?>... maps) {
        int total = 0;
        for (Map<?, ?> map : maps) {
            if (map != null) {
                if (map.size() > MAX_AGGREGATE_ENTRIES - total) throw invalid(code);
                total += map.size();
            }
        }
    }

    public static void requireText(String code, String... values) {
        for (String value : values) {
            if (value != null && value.codePointCount(0, value.length()) > MAX_TEXT_CODE_POINTS) {
                throw invalid(code);
            }
        }
    }

    @SafeVarargs
    public static void requireMapText(String code, Map<String, String>... maps) {
        for (Map<String, String> map : maps) {
            if (map != null) map.forEach((key, value) -> requireText(code, key, value));
        }
    }

    private static ClientRequestException invalid(String code) {
        return new ClientRequestException(code, "Business request input exceeds the allowed limit");
    }
}
