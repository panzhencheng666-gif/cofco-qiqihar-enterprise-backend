package com.cofco.qiqihar.graintrade.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.logistics.review.LogisticsBatchReview;
import com.cofco.qiqihar.graintrade.market.review.MarketBatchReview;
import com.cofco.qiqihar.graintrade.production.review.ProductionBatchReview;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.workflow.application.WorkItemBatchReviewService.BatchReviewQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkItemBatchReviewServiceTest {

    private final WorkItemReader reader = mock(WorkItemReader.class);
    private final AccessControl access = mock(AccessControl.class);
    private final ProductionBatchReview production = mock(ProductionBatchReview.class);
    private final MarketBatchReview market = mock(MarketBatchReview.class);
    private final LogisticsBatchReview logistics = mock(LogisticsBatchReview.class);
    private final WorkItemBatchReviewService service = new WorkItemBatchReviewService(
            reader, access, production, market, logistics);

    @Test
    void approvesEverySupportedRecordAndKeepsAnIndividualFailureForCorrection() {
        WorkItem productionItem = item("work-1", "PRODUCTION", "production-1");
        WorkItem marketItem = item("work-2", "MARKET", "market-1");
        WorkItem logisticsItem = item("work-3", "LOGISTICS", "logistics-1");
        when(reader.read(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PagedResult<>(List.of(productionItem, marketItem, logisticsItem), 0, 100, 3));

        org.mockito.Mockito.doThrow(new ConflictException("MARKET_REVIEW_BLOCKED", "库存权属尚未核定"))
                .when(market).approve("market-1");

        var result = service.approve(new BatchReviewQuery(null, null, null));

        assertThat(result.requestedCount()).isEqualTo(3);
        assertThat(result.approvedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.sourceId()).isEqualTo("market-1");
            assertThat(failure.reason()).contains("库存权属");
        });
        verify(access).require("BUSINESS_APPROVE", null);
        verify(production).approve("production-1");
        verify(logistics).approve("logistics-1");
    }

    @Test
    void rejectsNonAdministratorBeforeReadingAnyWorkItem() {
        when(access.require("BUSINESS_APPROVE", null))
                .thenThrow(new AccessDeniedException("ACCESS_PERMISSION_DENIED", "Operation permission is denied"));

        assertThatThrownBy(() -> service.approve(new BatchReviewQuery(null, null, null)))
                .isInstanceOf(AccessDeniedException.class);

        verify(reader, never()).read(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void approvesEveryRecordAcrossMoreThanOneWorkItemPage() {
        List<WorkItem> records = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            records.add(item("work-" + index, "PRODUCTION", "production-" + index));
        }
        when(reader.read(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            WorkItemQuery query = invocation.getArgument(0);
            int from = query.pageNumber() * 100;
            int to = Math.min(from + 100, records.size());
            return new PagedResult<>(records.subList(from, to), query.pageNumber(), 100, 201);
        });
        var result = service.approve(new BatchReviewQuery("PRODUCTION", null, null));

        assertThat(result.requestedCount()).isEqualTo(201);
        assertThat(result.approvedCount()).isEqualTo(201);
        assertThat(result.failedCount()).isZero();
        verify(reader, times(3)).read(org.mockito.ArgumentMatchers.any());
        verify(production, times(201)).approve(org.mockito.ArgumentMatchers.anyString());
    }

    private static WorkItem item(String id, String sourceType, String sourceId) {
        return new WorkItem(
                id, "待审核业务记录", sourceType, "230200", "齐齐哈尔市", "玉米",
                "2026-W32", "2026年第32周", OffsetDateTime.parse("2026-08-18T00:00:00+08:00"),
                "审核", "TO_REVIEW", "待审核", "LOCAL_DEV", "平台运营管理部",
                sourceType, sourceId);
    }
}
