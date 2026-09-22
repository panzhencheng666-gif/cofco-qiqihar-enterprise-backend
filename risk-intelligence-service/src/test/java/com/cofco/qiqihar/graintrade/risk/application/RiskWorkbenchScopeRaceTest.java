package com.cofco.qiqihar.graintrade.risk.application;

import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.riskintelligence.security.RiskRegionScope;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RiskWorkbenchScopeRaceTest {
    @Test void disappearingScopeBetweenExistenceAndInsertionStillLooksMissing() {
        var repository = mock(RiskWorkbenchRepository.class);
        UUID id = UUID.randomUUID();
        var scope = new RiskRegionScope(false, Set.of("230221"));
        when(repository.assessmentExists(id, scope)).thenReturn(true, false);
        var service = new RiskWorkbenchService(repository, Clock.systemUTC());
        assertThatThrownBy(() -> service.submitFeedback(id, "CONFIRMED", "TEST_ONLY", "fixture", "actor", scope))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
