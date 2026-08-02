package com.cofco.qiqihar.graintrade.production.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductionRecordServiceTest {

    @Test
    void doesNotMisclassifyAnInfrastructureRuntimeFailureAsClientValidation() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isKnownRegion("230202")).thenThrow(new RuntimeException("database unavailable"));
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("tester"));
        ProductionRecordService service = new ProductionRecordService(repository, mock(PageDefinitionQuery.class),
                actor, Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of());

        assertThatThrownBy(() -> service.create(draft))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void translatesOnlyTypedDomainValidationFailuresToAClientRequest() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isKnownRegion("230202")).thenReturn(true);
        when(repository.isApplicableObjectType("SOYBEAN", "FARMER")).thenReturn(true);
        when(repository.areApplicableFacts("SOYBEAN", "FARMER", Map.of(
                "QUALITY", java.util.Set.of(), "COST", java.util.Set.of(),
                "INSURANCE", java.util.Set.of(), "SUBSIDY", java.util.Set.of()))).thenReturn(true);
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("tester"));
        ProductionRecordService service = new ProductionRecordService(repository, mock(PageDefinitionQuery.class),
                actor, Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("-1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of());

        assertThatThrownBy(() -> service.create(draft))
                .isInstanceOf(ClientRequestException.class)
                .extracting("code")
                .isEqualTo("INVALID_PRODUCTION_RECORD");
    }
}
