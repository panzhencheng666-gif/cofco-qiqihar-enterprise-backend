package com.cofco.qiqihar.graintrade.logistics.application;
import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
public interface LogisticsRepository {
    PagedResult<LogisticsRecordView> findPage(String productCode, int pageNumber, int pageSize, Map<String,String> filters);
    LogisticsRecordView find(String id);
    LogisticsDefinitionView definition(String productCode);
    boolean validDraft(LogisticsDraft draft, java.time.LocalDate today);
    boolean actionAllowed(String productCode, LogisticsStatus status, String actionCode);
    Set<String> regionsForDraft(LogisticsDraft draft);
    Set<String> regionsForRecord(String id);
    LogisticsRecordView insert(String id, LogisticsDraft draft, String actor, Instant now);
    LogisticsRecordView update(String id, long version, LogisticsDraft draft, String actor, Instant now);
    LogisticsRecordView transition(String id, long version, LogisticsStatus status, String reason, String actor, Instant now);
}
