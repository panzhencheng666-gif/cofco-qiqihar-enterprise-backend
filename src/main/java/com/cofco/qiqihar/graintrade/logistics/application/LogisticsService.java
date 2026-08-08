package com.cofco.qiqihar.graintrade.logistics.application;

import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogisticsService {
    private static final Set<String> FILTERS = Set.of("regionCode","periodCode","nodeTypeCode","transportModeCode","status");
    private final LogisticsRepository repository;
    private final PageDefinitionQuery pages;
    private final CurrentActor currentActor;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final Clock clock;
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor, Clock clock) {
        this(repository, pages, currentActor, null, null, clock);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor,
            AccessControl accessControl, BusinessAuditRecorder audit, Clock clock) {
        this.repository=repository; this.pages=pages; this.currentActor=currentActor; this.accessControl=accessControl; this.audit=audit; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public PagedResult<LogisticsRecordView> list(String productCode, int pageNumber, int pageSize, Map<String,String> filters) {
        if (pageNumber < 0 || pageSize < 1 || filters.keySet().stream().anyMatch(k -> !FILTERS.contains(k))
                || !pages.allowsListQueryValues("LOGISTICS","MONITORING",productCode,pageSize,filters)) throw invalid();
        AuthorizedReadScope scope=readScope();
        if(filters.get("regionCode")!=null)scope.requireRegion(filters.get("regionCode"));
        return repository.findPage(productCode,pageNumber,pageSize,filters,scope.regionCodes());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public LogisticsRecordView detail(String id) {
        LogisticsRecordView record=required(id); AuthorizedReadScope scope=readScope();
        repository.regionsForRecord(id).forEach(scope::requireRegion); return record;
    }
    @Transactional(readOnly=true) public LogisticsDefinitionView definition(String productCode) {
        LogisticsDefinitionView definition=repository.definition(productCode);if(definition==null)throw invalid();return definition;
    }
    @Transactional public LogisticsRecordView create(LogisticsDraft draft) {
        validate(draft); SecurityPrincipal principal=authorize("BUSINESS_CREATE",repository.regionsForDraft(draft));
        if(!repository.actionAllowed(draft.productCode(),LogisticsStatus.DRAFT,"NEW"))throw invalid();
        LogisticsRecordView created=repository.insert(UUID.randomUUID().toString(),draft,principal.subjectId(),clock.instant()); audit(principal,created,"LOGISTICS_RECORD_CREATED"); return created;
    }
    @Transactional public LogisticsRecordView save(String id,long version,LogisticsDraft draft) {
        LogisticsRecordView existing=required(id); SecurityPrincipal principal=authorize("BUSINESS_UPDATE",repository.regionsForRecord(id)); requireVersion(existing,version);
        if (!existing.productCode().equals(draft.productCode())) throw invalid();
        if (existing.status()!=LogisticsStatus.DRAFT && existing.status()!=LogisticsStatus.RETURNED) throw invalid();
        if(!repository.actionAllowed(existing.productCode(),existing.status(),"SAVE"))throw invalid();
        validate(draft); authorize("BUSINESS_UPDATE",repository.regionsForDraft(draft)); LogisticsRecordView updated=repository.update(id,version,draft,principal.subjectId(),clock.instant()); audit(principal,updated,"LOGISTICS_RECORD_UPDATED"); return updated;
    }
    @Transactional public LogisticsRecordView submit(String id,long version) { return transition(id,version,LogisticsStatus.PENDING_REVIEW,null,"BUSINESS_SUBMIT","LOGISTICS_RECORD_SUBMITTED"); }
    @Transactional public LogisticsRecordView approve(String id,long version) { return transition(id,version,LogisticsStatus.APPROVED,null,"BUSINESS_APPROVE","LOGISTICS_RECORD_APPROVED"); }
    @Transactional public LogisticsRecordView returned(String id,long version,String reason) {
        if (reason==null || reason.isBlank()) throw invalid(); return transition(id,version,LogisticsStatus.RETURNED,reason.trim(),"BUSINESS_RETURN","LOGISTICS_RECORD_RETURNED");
    }
    private LogisticsRecordView transition(String id,long version,LogisticsStatus target,String reason,String permission,String auditAction) {
        LogisticsRecordView existing=required(id); SecurityPrincipal principal=authorize(permission,repository.regionsForRecord(id)); requireVersion(existing,version);
        boolean allowed=(target==LogisticsStatus.PENDING_REVIEW && existing.status()==LogisticsStatus.DRAFT)
                || ((target==LogisticsStatus.APPROVED || target==LogisticsStatus.RETURNED) && existing.status()==LogisticsStatus.PENDING_REVIEW);
        String action=target==LogisticsStatus.PENDING_REVIEW?"SUBMIT":target==LogisticsStatus.APPROVED?"APPROVE":"RETURN";
        if(!allowed||!repository.actionAllowed(existing.productCode(),existing.status(),action)) throw invalid();
        LogisticsRecordView updated=repository.transition(id,version,target,reason,principal.subjectId(),clock.instant()); audit(principal,updated,auditAction); return updated;
    }
    private void validate(LogisticsDraft draft) {
        if (draft==null || blank(draft.productCode())
                || !repository.validDraft(draft,java.time.LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))) throw invalid();
    }
    private String actor(){return currentActor.currentActor().orElseThrow(AuthenticationRequiredException::new).id();}
    private SecurityPrincipal authorize(String permission,Set<String> regions){
        if(regions.isEmpty()) throw invalid();
        SecurityPrincipal principal=accessControl==null?new SecurityPrincipal(actor(),"UNIT_TEST",Set.of(),Set.of()):accessControl.require(permission,regions.iterator().next());
        if(accessControl!=null) regions.forEach(region->accessControl.require(permission,region));
        return principal;
    }
    private AuthorizedReadScope readScope(){return accessControl==null?AuthorizedReadScope.unrestricted():accessControl.requireReadScope();}
    private void audit(SecurityPrincipal principal,LogisticsRecordView record,String action){if(audit!=null)audit.record(principal,"LOGISTICS_RECORD",record.id(),action,clock.instant(),"{}");}
    private LogisticsRecordView required(String id){LogisticsRecordView value=repository.find(id); if(value==null) throw new ResourceNotFoundException("LOGISTICS_RECORD_NOT_FOUND","Logistics record was not found"); return value;}
    private static void requireVersion(LogisticsRecordView value,long version){if(version<0||value.version()!=version)throw new ConflictException("LOGISTICS_RECORD_VERSION_CONFLICT","Logistics record has changed");}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static ClientRequestException invalid(){return new ClientRequestException("INVALID_LOGISTICS_RECORD","Logistics record or query is invalid");}
}
