package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RegionalHierarchyMemoryTest {
    @Test void batchDoesNotRetainEveryCompletedProfile() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        var profiles = mock(RegionalAgricultureProfileService.class);
        var peak = new AtomicInteger();
        var calculated = new AtomicInteger();
        when(profiles.profileForRefresh(eq(2026), anyString(), anyMap())).thenAnswer(call -> {
            String code = call.getArgument(1);
            Map<String, RegionalAgricultureProfile> cache = call.getArgument(2);
            var profile = new RegionalAgricultureProfile(code, code, "VILLAGE", 2026, true,
                    "2026-09-24T00:00:00Z", "", new RegionalAgricultureProfile.RegionFacts(
                    BigDecimal.ONE, 0, 0, 0, 0), "", "", null, null,
                    List.of(), List.of(), List.of(), List.of());
            cache.put(code, profile);
            peak.accumulateAndGet(cache.size(), Math::max);
            calculated.incrementAndGet();
            return profile;
        });
        var regions = IntStream.range(0, 2601).mapToObj(i -> "region-" + i).toList();
        new RegionalHierarchyRefresh(jdbc, profiles).refreshRegions(regions, Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(calculated.get()).isEqualTo(2601);
        assertThat(peak.get()).as("retained complete profiles while refreshing all regions").isLessThanOrEqualTo(32);
    }
}
