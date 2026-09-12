package com.cofco.qiqihar.graintrade.shared.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cofco.qiqihar.graintrade.production.application.*;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.market.application.*;
import com.cofco.qiqihar.graintrade.market.domain.MarketRecordQuery;
import com.cofco.qiqihar.graintrade.logistics.application.*;
import com.cofco.qiqihar.graintrade.formalsampleobservation.application.*;
import com.cofco.qiqihar.graintrade.shared.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BusinessTaskQueryScopeTest {
    private final AccessControl access = mock(AccessControl.class);
    private final PageDefinitionQuery pages = mock(PageDefinitionQuery.class);

    private void scopes(Set<String> tasks) {
        when(access.requireBusinessReadScope()).thenReturn(new AuthorizedReadScope("employee", Set.of("*")));
        when(access.requireTaskReadScope()).thenReturn(new AuthorizedReadScope("employee", tasks));
        when(pages.allowsListQueryValues(anyString(), anyString(), anyString(), anyInt(), anyMap())).thenReturn(true);
    }

    @Test void productionScopesPaginationAndCountBeforeRepositoryRead() {
        scopes(Set.of("230221101"));
        var repo = mock(ProductionRecordRepository.class);
        when(repo.findPage(any())).thenAnswer(call -> {
            ProductionRecordQuery q = call.getArgument(0);
            assertThat(q.pageNumber()).isEqualTo(2);
            assertThat(q.pageSize()).isEqualTo(20);
            return new PagedResult<ProductionListRow>(List.of(), 2, 20, q.authorizedRegionCodes().contains("*") ? 60 : 41);
        });
        var service = new ProductionRecordService(repo, pages, null, access, null, null, null, null, Clock.systemUTC());
        var query = new ProductionRecordQuery("CORN", "MONITORING", 2, 20, Map.of());
        assertThat(service.read(query).totalElements()).isEqualTo(60);
        assertThat(service.read(query, "MY_TASKS").totalElements()).isEqualTo(41);
        verify(repo).findPage(argThat(q -> q.authorizedRegionCodes().equals(Set.of("230221101"))));
        assertThatThrownBy(() -> service.read(query, "ALL_USERS")).isInstanceOf(ClientRequestException.class);
    }

    @Test void globalProductionRowsExposeMutationsOnlyInAssignedRegion() {
        scopes(Set.of("230221101"));
        var principal = new com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal(
                "employee", "UNIT", Set.of("BUSINESS_READ", "BUSINESS_UPDATE", "BUSINESS_SUBMIT"), Set.of("230221101"));
        when(access.authenticated()).thenReturn(Optional.of(principal));
        var repo = mock(ProductionRecordRepository.class);
        var status = com.cofco.qiqihar.graintrade.production.domain.ProductionStatus.DRAFT;
        when(repo.findPage(any())).thenReturn(new PagedResult<>(List.of(
                new ProductionListRow("inside", Map.of("PROD_REGION", "甲乡"), status, Set.of("VIEW", "SAVE", "SUBMIT"), 0, "230221101"),
                new ProductionListRow("outside", Map.of("PROD_REGION", "乙乡"), status, Set.of("VIEW", "SAVE", "SUBMIT"), 0, "230221102")), 0, 20, 2));
        var service = new ProductionRecordService(repo, pages, null, access, null, null, null, null, Clock.systemUTC());
        var result = service.read(new ProductionRecordQuery("CORN", "MONITORING", 0, 20, Map.of()));
        assertThat(result.items().get(0).allowedActions()).contains("SAVE", "SUBMIT");
        assertThat(result.items().get(1).allowedActions()).containsExactly("VIEW");
        verify(repo, never()).findById(anyString());
    }

    @Test void marketPassesEmptyAssignmentWithoutFallingBackToGlobal() {
        scopes(Set.of());
        var repo = mock(MarketMonitoringRepository.class);
        when(repo.findPage(any())).thenReturn(new PagedResult<>(List.of(), 0, 20, 0));
        var service = new MarketMonitoringService(repo, pages, null, access, null, Clock.systemUTC());
        service.list(new MarketRecordQuery("CORN", "MONITORING", 0, 20, Map.of()), "MY_TASKS");
        verify(repo).findPage(argThat(q -> q.authorizedRegionCodes().isEmpty()));
        verify(access, never()).requireBusinessReadScope();
    }

    @Test void logisticsUsesCurrentScopeForTaskRowsAndCount() {
        scopes(Set.of("230221101"));
        var repo = mock(LogisticsRepository.class);
        when(repo.findPage(anyString(), anyInt(), anyInt(), anyMap(), anySet()))
                .thenReturn(new PagedResult<>(List.of(), 0, 20, 0));
        var service = new LogisticsService(repo, pages, null, access, null, null, Clock.systemUTC());
        service.list("CORN", 0, 20, Map.of(), "MY_TASKS");
        verify(repo).findPage("CORN", 0, 20, Map.of(), Set.of("230221101"));
        service.list("CORN", 0, 20, Map.of());
        verify(repo).findPage("CORN", 0, 20, Map.of(), Set.of("*"));
    }

    @Test void eligibleTasksUseCurrentRegionWithoutHistoricalMaintainerRestriction() {
        scopes(Set.of("230221101"));
        var repo = mock(FormalSampleObservationRepository.class);
        var service = new FormalSampleObservationService(repo, access, null, null, null, null, null, Clock.systemUTC(), null);
        var at = OffsetDateTime.parse("2026-09-12T12:00:00+08:00");
        service.eligibleSamples(FormalSampleObservationDomain.PRODUCTION, "CORN", null, null, null, 2026, at, "MY_TASKS");
        verify(repo).findEligibleSamples(FormalSampleObservationDomain.PRODUCTION, "CORN", null, null, null,
                LocalDate.of(2026, 9, 12), Set.of("230221101"), "employee", true);
        service.eligibleSamples(FormalSampleObservationDomain.PRODUCTION, "CORN", null, null, null, 2026, at);
        verify(repo).findEligibleSamples(FormalSampleObservationDomain.PRODUCTION, "CORN", null, null, null,
                LocalDate.of(2026, 9, 12), Set.of("*"), "employee", true);
    }
}
