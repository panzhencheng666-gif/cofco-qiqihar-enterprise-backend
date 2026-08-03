package com.cofco.qiqihar.graintrade.logistics.application;

import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogisticsService {
    private static final Set<String> FILTERS = Set.of("regionCode","periodCode","nodeTypeCode","transportModeCode","status");
    private final LogisticsRepository repository;
    private final PageDefinitionQuery pages;
    private final CurrentActor currentActor;
    private final Clock clock;
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor, Clock clock) {
        this.repository=repository; this.pages=pages; this.currentActor=currentActor; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public PagedResult<LogisticsRecordView> list(String productCode, int pageNumber, int pageSize, Map<String,String> filters) {
        if (pageNumber < 0 || pageSize < 1 || filters.keySet().stream().anyMatch(k -> !FILTERS.contains(k))
                || !pages.allowsListQueryValues("LOGISTICS","MONITORING",productCode,pageSize,filters)) throw invalid();
        return repository.findPage(productCode,pageNumber,pageSize,filters);
    }
    @Transactional(readOnly=true) public LogisticsRecordView detail(String id) { return required(id); }
    @Transactional(readOnly=true) public LogisticsDefinitionView definition(String productCode) {
        LogisticsDefinitionView definition=repository.definition(productCode);if(definition==null)throw invalid();return definition;
    }
    @Transactional public LogisticsRecordView create(LogisticsDraft draft) {
        String actor=actor(); validate(draft); return repository.insert(UUID.randomUUID().toString(),draft,actor,clock.instant());
    }
    @Transactional public LogisticsRecordView save(String id,long version,LogisticsDraft draft) {
        String actor=actor(); LogisticsRecordView existing=required(id); requireVersion(existing,version);
        if (!existing.productCode().equals(draft.productCode())) throw invalid();
        if (existing.status()!=LogisticsStatus.DRAFT && existing.status()!=LogisticsStatus.RETURNED) throw invalid();
        validate(draft); return repository.update(id,version,draft,actor,clock.instant());
    }
    @Transactional public LogisticsRecordView submit(String id,long version) { return transition(id,version,LogisticsStatus.PENDING_REVIEW,null); }
    @Transactional public LogisticsRecordView approve(String id,long version) { return transition(id,version,LogisticsStatus.APPROVED,null); }
    @Transactional public LogisticsRecordView returned(String id,long version,String reason) {
        if (reason==null || reason.isBlank()) throw invalid(); return transition(id,version,LogisticsStatus.RETURNED,reason.trim());
    }
    private LogisticsRecordView transition(String id,long version,LogisticsStatus target,String reason) {
        String actor=actor(); LogisticsRecordView existing=required(id); requireVersion(existing,version);
        boolean allowed=(target==LogisticsStatus.PENDING_REVIEW && existing.status()==LogisticsStatus.DRAFT)
                || ((target==LogisticsStatus.APPROVED || target==LogisticsStatus.RETURNED) && existing.status()==LogisticsStatus.PENDING_REVIEW);
        if(!allowed) throw invalid(); return repository.transition(id,version,target,reason,actor,clock.instant());
    }
    private void validate(LogisticsDraft draft) {
        if (draft==null || blank(draft.productCode())
                || !repository.validDraft(draft,java.time.LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))) throw invalid();
    }
    private String actor(){return currentActor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();}
    private LogisticsRecordView required(String id){LogisticsRecordView value=repository.find(id); if(value==null) throw new ResourceNotFoundException("LOGISTICS_RECORD_NOT_FOUND","Logistics record was not found"); return value;}
    private static void requireVersion(LogisticsRecordView value,long version){if(version<0||value.version()!=version)throw new ConflictException("LOGISTICS_RECORD_VERSION_CONFLICT","Logistics record has changed");}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalid(){return new ClientRequestException("INVALID_LOGISTICS_RECORD","Logistics record or query is invalid");}
}
