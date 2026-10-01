package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsSearchBudgetTest {
    @TempDir Path temporary;
    private static final Instant END = Instant.parse("2026-09-28T16:41:50Z");
    private static final Instant NOW = END.minusSeconds(600);

    @Test void survivesReopenWithoutRefundingUnknownRequests() throws Exception {
        Path path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, 2);
        assertThat(new NewsSearchBudget(path, END, 2).reserve("CNLiteBasic", NOW)).isTrue();
        var reopened = new NewsSearchBudget(path, END, 2);
        assertThat(reopened.reserve("CNLiteBasic", NOW)).isTrue();
        assertThat(reopened.reserve("CNLiteBasic", NOW)).isFalse();
        assertThat(reopened.reserve("GlobalAdvanced", NOW)).isTrue();
    }

    @Test void concurrentObjectsCannotExceedLimit() throws Exception {
        Path path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, 3);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() ->
                new NewsSearchBudget(path, END, 3).reserve("GlobalAdvanced", NOW)));
            int granted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) granted++;
            assertThat(granted).isEqualTo(3);
        }
    }

    @Test void deadlineAndEngineRejectBeforeReservation() throws Exception {
        Path path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, 1);
        var budget = new NewsSearchBudget(path, END, 1);
        assertThat(budget.reserve("CNLiteBasic", END)).isFalse();
        assertThatThrownBy(() -> budget.reserve("../Generic", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.reserve("CNLiteBasic", NOW)).isTrue();
    }

    @Test void configurationChangesAndReinitializationFailClosed() throws Exception {
        Path path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, 1);
        assertThatThrownBy(() -> NewsSearchBudget.initialize(path, END, 2)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new NewsSearchBudget(path, END.plusSeconds(1), 1)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new NewsSearchBudget(path, END, 2)).isInstanceOf(java.io.IOException.class);
    }

    @Test void missingManifestAndSymlinkDirectoryFailClosed() throws Exception {
        Path path = temporary.resolve("budget");
        NewsSearchBudget.initialize(path, END, 1);
        Path link = temporary.resolve("alias");
        Files.createSymbolicLink(link, path);
        assertThatThrownBy(() -> new NewsSearchBudget(link, END, 1)).isInstanceOf(java.io.IOException.class);
        Files.delete(path.resolve("manifest"));
        assertThatThrownBy(() -> new NewsSearchBudget(path, END, 1)).isInstanceOf(java.io.IOException.class);
    }
    @Test void resetsDailyAtBeijingMidnightButNeverResetsTotalCost() throws Exception {
        Path path = temporary.resolve("daily");
        NewsSearchBudget.initialize(path, END, 100);
        var budget = new NewsSearchBudget(path, END, 100);
        Instant first = END.minusSeconds(6 * 86400L);
        for (int i=0; i<100; i++) assertThat(budget.reserve("GlobalAdvanced", first)).isTrue();
        assertThat(budget.reserve("GlobalAdvanced", first)).isFalse();
        Instant midnight = first.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
            .plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        assertThat(budget.reserve("GlobalAdvanced", midnight.minusNanos(1))).isFalse();
        for (int i=0; i<78; i++) assertThat(budget.reserve("GlobalAdvanced", midnight)).isTrue();
        assertThat(budget.reserve("GlobalAdvanced", midnight)).isFalse();
        // 178 * 56 + 4 * 8 = 10000 milli-yuan. Reopen never refunds consumption.
        var reopened = new NewsSearchBudget(path, END, 100);
        for (int i=0; i<4; i++) assertThat(reopened.reserve("CNLiteBasic", midnight)).isTrue();
        assertThat(reopened.reserve("CNLiteBasic", midnight.plusSeconds(86400))).isFalse();
    }
    @Test void rejectsBeforeWindowAndClockRollback() throws Exception {
        Path path = temporary.resolve("clock");
        NewsSearchBudget.initialize(path, END, 100);
        var budget = new NewsSearchBudget(path, END, 100);
        assertThat(budget.reserve("CNLiteBasic", END.minusSeconds(7 * 86400L + 1))).isFalse();
        assertThat(budget.reserve("CNLiteBasic", NOW)).isTrue();
        assertThat(budget.reserve("CNLiteBasic", NOW.minusSeconds(1))).isFalse();
    }
    @Test void corruptReservationFailsClosedWithoutRefund() throws Exception {
        Path path = temporary.resolve("corrupt");
        NewsSearchBudget.initialize(path, END, 100);
        Files.writeString(path.resolve("request-1.reserved"), "partial");
        assertThatThrownBy(() -> new NewsSearchBudget(path, END, 100).reserve("CNLiteBasic", NOW))
            .isInstanceOf(java.io.IOException.class);
    }
}
