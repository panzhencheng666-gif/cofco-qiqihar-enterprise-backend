package com.cofco.qiqihar.graintrade.supply.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SupplyAccountServiceTest {

    @Test
    void translatesFormulaConstructionFailureAtTheRepositoryBoundaryToTheStableContract() {
        SupplyAccountRepository repository = mock(SupplyAccountRepository.class);
        when(repository.loadCalculationMaterial("set-1", "CORN", "230200", "2026/27"))
                .thenThrow(new IllegalArgumentException("Invalid supply formula result"));
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("reviewer"));
        SupplyAccountService service = new SupplyAccountService(repository, actor,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC), mock(ObjectMapper.class));
        SupplyRunCommand command = new SupplyRunCommand(
                "CORN", "230200", "2026/27", "set-1", new BigDecimal("1.000"), "调整建议", 0, true);

        assertThatThrownBy(() -> service.run(command))
                .isInstanceOf(ServerContractException.class)
                .extracting("code")
                .isEqualTo("INVALID_SUPPLY_FORMULA_METADATA");
    }
}
