package com.cofco.qiqihar.graintrade.workflow.application;

import com.cofco.qiqihar.graintrade.logistics.review.LogisticsBatchReview;
import com.cofco.qiqihar.graintrade.market.review.MarketBatchReview;
import com.cofco.qiqihar.graintrade.production.review.ProductionBatchReview;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItem;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemQuery;
import com.cofco.qiqihar.graintrade.workflow.domain.WorkItemScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Applies the existing governed per-record approval command to every pending work item in one
 * authorized filter scope. The individual domain services remain the transaction and audit
 * boundary, so one invalid record is retained for correction without rolling back valid approvals.
 */
@Service
public class WorkItemBatchReviewService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 5_000;

    private final WorkItemReader reader;
    private final AccessControl accessControl;
    private final ProductionBatchReview production;
    private final MarketBatchReview market;
    private final LogisticsBatchReview logistics;

    public WorkItemBatchReviewService(
            WorkItemReader reader,
            AccessControl accessControl,
            ProductionBatchReview production,
            MarketBatchReview market,
            LogisticsBatchReview logistics) {
        this.reader = reader;
        this.accessControl = accessControl;
        this.production = production;
        this.market = market;
        this.logistics = logistics;
    }

    public BatchReviewResult approve(BatchReviewQuery query) {
        accessControl.require("BUSINESS_APPROVE", null);
        List<WorkItem> items = pendingReviewItems(query);
        List<BatchReviewFailure> failures = new ArrayList<>();
        int approved = 0;
        for (WorkItem item : items) {
            try {
                approve(item);
                approved++;
            } catch (RuntimeException exception) {
                if (!isRecordLevelFailure(exception)) throw exception;
                failures.add(new BatchReviewFailure(
                        item.sourceType(), item.sourceId(), safeMessage(exception)));
            }
        }
        return new BatchReviewResult(items.size(), approved, failures.size(), List.copyOf(failures));
    }

    private List<WorkItem> pendingReviewItems(BatchReviewQuery query) {
        PagedResult<WorkItem> first = reader.read(workItemQuery(query, 0));
        if (first.totalElements() > MAX_BATCH_SIZE) {
            throw new ClientRequestException(
                    "BATCH_REVIEW_TOO_LARGE", "Batch review scope exceeds the supported limit");
        }
        List<WorkItem> items = new ArrayList<>(first.items());
        for (int page = 1; page < first.totalPages(); page++) {
            items.addAll(reader.read(workItemQuery(query, page)).items());
        }
        return List.copyOf(items);
    }

    private static WorkItemQuery workItemQuery(BatchReviewQuery query, int page) {
        return WorkItemQuery.of(
                WorkItemScope.PENDING,
                "TO_REVIEW",
                query.domain(),
                query.regionId(),
                query.productCode(),
                page,
                PAGE_SIZE);
    }

    private void approve(WorkItem item) {
        String sourceType = item.sourceType() == null
                ? "" : item.sourceType().trim().toUpperCase(Locale.ROOT);
        String sourceId = item.sourceId();
        if (sourceId == null || sourceId.isBlank()) {
            throw new ClientRequestException(
                    "WORK_ITEM_SOURCE_REQUIRED", "Work item has no reviewable business record");
        }
        switch (sourceType) {
            case "PRODUCTION" -> {
                production.approve(sourceId);
            }
            case "MARKET" -> {
                market.approve(sourceId);
            }
            case "LOGISTICS" -> {
                logistics.approve(sourceId);
            }
            default -> throw new ClientRequestException(
                    "WORK_ITEM_BATCH_REVIEW_UNSUPPORTED",
                    "Work item does not support business-record batch review");
        }
    }

    private static boolean isRecordLevelFailure(RuntimeException exception) {
        return exception instanceof AccessDeniedException
                || exception instanceof ClientRequestException
                || exception instanceof ConflictException
                || exception instanceof ResourceNotFoundException;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "审核条件不满足" : message;
    }

    public record BatchReviewQuery(String domain, String regionId, String productCode) { }

    public record BatchReviewResult(
            int requestedCount,
            int approvedCount,
            int failedCount,
            List<BatchReviewFailure> failures) { }

    public record BatchReviewFailure(String sourceType, String sourceId, String reason) { }
}
