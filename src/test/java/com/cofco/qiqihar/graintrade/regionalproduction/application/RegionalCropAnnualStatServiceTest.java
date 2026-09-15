package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegionalCropAnnualStatServiceTest {
    @Test
    void unassignedEmployeeCreatesAndUpdatesCountyDataWithVersionAndAudit() {
        var principal = new SecurityPrincipal("employee", "unit", Set.of(), Set.of());
        var access = new AccessControl(() -> Optional.of("employee"), id -> Optional.of(principal), true);
        var repository = mock(RegionalCropAnnualStatRepository.class);
        var audit = mock(BusinessAuditRecorder.class);
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var service = new RegionalCropAnnualStatService(repository, access, audit, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
        when(repository.region("231121")).thenReturn(Optional.of(new RegionalCropAnnualStatRepository.RegionDescriptor("231121", "嫩江县", "231100", "COUNTY")));
        when(repository.knownProduct("CORN")).thenReturn(true);
        for (long version : new long[] {0, 1}) {
            var area = new BigDecimal("100.0000");
            var yield = new BigDecimal("500.0000");
            var saved = new RegionalCropAnnualStat("231121", "嫩江县", "231100", 2026, "CORN", area, yield, new BigDecimal("50000"), version + 1, now);
            when(repository.upsert("231121", 2026, "CORN", area, yield, version, "employee", now)).thenReturn(Optional.of(saved));
            assertThat(service.upsert("231121", 2026, "CORN", area, yield, version)).isEqualTo(saved);
        }
        verify(audit, times(2)).record(eq(principal), eq("REGIONAL_CROP_ANNUAL_STAT"), anyString(), eq("REGIONAL_CROP_ANNUAL_STAT_UPSERTED"), eq(now), any());
        assertThatThrownBy(() -> service.upsert("231121", 2026, "CORN", BigDecimal.ONE, BigDecimal.ONE, 99)).isInstanceOf(ConflictException.class);
    }
}
