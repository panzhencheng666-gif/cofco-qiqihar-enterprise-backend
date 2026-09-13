package com.cofco.qiqihar.graintrade.overview.application;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
class OverviewSharedReadTest {
    @Test
    void sampleListUsesAllRegionsEvenWhenAccountHasNoResponsibility() {
        var samples = mock(OverviewSamplePointRepository.class);
        var overview = mock(OverviewRepository.class);
        when(overview.knownProduct("CORN")).thenReturn(true);
        when(overview.knownRegion("231100")).thenReturn(true);
        var principal = new SecurityPrincipal("ordinary", "TEST", Set.of(), Set.of());
        var access = new AccessControl(() -> Optional.of("ordinary"), id -> Optional.of(principal), true);
        var service = new OverviewSamplePointService(samples, overview, access);
        service.list(2026, "CORN", "231100", null, null, null);
        verify(samples).list(2026, "CORN", "231100", null, null, null, Set.of("*"));
        assertThat(principal.regionCodes()).isEmpty();
        var regions = new OverviewService(overview, access);
        regions.regions("231100", "CORN", 2026, null);
        verify(overview).regions("231100", "CORN", 2026, Set.of("*"));
    }
}
