package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class RegionalDailyScheduleTest {
    @Test void triggersAtShanghaiEightThirtyAndRefreshesBeforeRecalculating() throws Exception {
        var annotation = RegionalPublicDataRefreshWorker.class.getMethod("refreshDueSources").getAnnotation(Scheduled.class);
        assertThat(annotation.zone()).isEqualTo("Asia/Shanghai");
        String expression = annotation.cron().substring(annotation.cron().indexOf(':')+1,annotation.cron().length()-1);
        var before = ZonedDateTime.of(2026,9,15,8,29,59,0,ZoneId.of(annotation.zone()));
        assertThat(CronExpression.parse(expression).next(before)).isEqualTo(before.plusSeconds(1));
        var source = mock(RegionalPublicDataRepository.class);
        var discovery = mock(RegionalSourceDiscovery.class);
        var batches = mock(RegionalEstimateBatchService.class);
        var hierarchy = mock(RegionalHierarchyRefresh.class);
        when(source.due(any())).thenReturn(List.of());
        new RegionalPublicDataRefreshWorker(source,discovery,batches,hierarchy).refreshDueSources();
        var order = inOrder(discovery,source,batches,hierarchy);
        order.verify(discovery).markDailyDue(any());
        order.verify(discovery).discover(any());
        order.verify(source).due(any());
        order.verify(batches).refresh(any());
        order.verify(hierarchy).refresh(any());
    }
}
