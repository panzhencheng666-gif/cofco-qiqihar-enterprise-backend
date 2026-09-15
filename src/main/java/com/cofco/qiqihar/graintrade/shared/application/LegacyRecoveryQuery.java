package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Map;
import java.util.Set;

/** Recovery never broadens the ordinary formal-data query. */
public final class LegacyRecoveryQuery {
    private LegacyRecoveryQuery() {}
    public static boolean requested(String value, Map<String, String> filters) {
        if (value == null) return false;
        if (!"true".equals(value) || !Set.of("DRAFT", "PENDING_REVIEW", "RETURNED")
                .contains(filters.getOrDefault("status", ""))) {
            throw new ClientRequestException("INVALID_RECOVERY_QUERY", "请选择待校验、待修正或旧草稿状态。");
        }
        return true;
    }
}
