package com.cofco.qiqihar.graintrade.logistics.application;

import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.shared.application.FormalSampleIdentity;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogisticsService {
    private static final Set<String> FILTERS = Set.of(
            "regionCode", "transportModeCode", "status",
            "surveyYear", "surveyMonth", "fillingDateFrom", "fillingDateTo");
    private final LogisticsRepository repository;
    private final PageDefinitionQuery pages;
    private final CurrentActor currentActor;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final Clock clock;
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor, Clock clock) {
        this(repository, pages, currentActor, null, null, null, clock);
    }
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor,
            AccessControl accessControl, BusinessAuditRecorder audit, Clock clock) {
        this(repository, pages, currentActor, accessControl, audit, null, clock);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LogisticsService(LogisticsRepository repository, PageDefinitionQuery pages, CurrentActor currentActor,
            AccessControl accessControl, BusinessAuditRecorder audit,
            SeparationOfDutiesPolicy separationOfDuties, Clock clock) {
        this.repository=repository; this.pages=pages; this.currentActor=currentActor; this.accessControl=accessControl;
        this.audit=audit; this.separationOfDuties=separationOfDuties; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public PagedResult<LogisticsRecordView> list(String productCode, int pageNumber, int pageSize, Map<String,String> filters) {
        return list(productCode,pageNumber,pageSize,filters,null);
    }
    @Transactional(readOnly=true)
    public PagedResult<LogisticsRecordView> list(String productCode, int pageNumber, int pageSize, Map<String,String> filters, String requestedScope) {
        if(requestedScope!=null && !"MY_TASKS".equals(requestedScope))throw invalid();
        if (pageNumber < 0 || pageSize < 1 || filters.keySet().stream().anyMatch(k -> !FILTERS.contains(k))
                || !pages.allowsListQueryValues("LOGISTICS","MONITORING",productCode,pageSize,filters)) throw invalid();
        AuthorizedReadScope scope="MY_TASKS".equals(requestedScope) && accessControl!=null ? accessControl.requireTaskReadScope():readScope();
        if(filters.get("regionCode")!=null)scope.requireRegion(filters.get("regionCode"));
        PagedResult<LogisticsRecordView> page=repository.findPage(productCode,pageNumber,pageSize,filters,scope.regionCodes());
        return new PagedResult<>(page.items().stream().map(this::authorizedView).toList(),
                page.pageNumber(),page.pageSize(),page.totalElements());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public LogisticsRecordView detail(String id) {
        LogisticsRecordView record=required(id); AuthorizedReadScope scope=readScope();
        repository.regionsForRecord(id).forEach(scope::requireRegion); return authorizedView(record);
    }
    @Transactional(readOnly=true) public LogisticsDefinitionView definition(String productCode) {
        LogisticsDefinitionView definition=repository.definition(productCode);if(definition==null)throw invalid();return definition;
    }
    @Transactional public LogisticsRecordView create(LogisticsDraft draft) {
        SecurityPrincipal principal=authorize("BUSINESS_CREATE",repository.regionsForDraft(draft));
        LogisticsDraft securedDraft=withReporter(draft,principal.displayName());
        validate(securedDraft);
        if(!repository.actionAllowed(securedDraft.productCode(),LogisticsStatus.DRAFT,"NEW"))throw invalid();
        LogisticsRecordView created=repository.insert(UUID.randomUUID().toString(),securedDraft,principal.subjectId(),clock.instant()); audit(principal,created,"LOGISTICS_RECORD_CREATED"); return persistValidated(created,principal);
    }
    @Transactional public LogisticsRecordView saveOfficialObservation(
            FormalSampleIdentity identity, OffsetDateTime observedAt,
            LogisticsDraft incoming, Instant officialSavedAt) {
        SecurityPrincipal principal=authorize("BUSINESS_CREATE",Set.of(identity.regionCode()));
        if(observedAt.toInstant().isAfter(officialSavedAt))throw invalid();
        java.time.LocalDate observedOn=observedAt.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        Map<String,String> values=new java.util.LinkedHashMap<>(incoming.values());
        values.put("surveyYear",Integer.toString(observedOn.getYear()));
        values.put("surveyMonth",Integer.toString(observedOn.getMonthValue()));
        values.put("LOG_REGION",identity.regionCode());
        values.put("LOG_REPORTER",principal.displayName());
        values.put("LOG_SAMPLE_NAME",identity.sampleName());
        values.put("LOG_SAMPLE_CONTACT",requiredLockedValue(identity,"LOG_SAMPLE_CONTACT"));
        values.put("LOG_SAMPLE_LATITUDE",coordinate(identity.latitude(),6));
        values.put("LOG_SAMPLE_LONGITUDE",coordinate(identity.longitude(),6));
        LogisticsDraft secured=new LogisticsDraft(identity.productCode(),values);
        validate(secured);
        return repository.insertOfficialObservation(UUID.randomUUID().toString(),secured,
                identity.samplePointId(),principal.subjectId(),observedAt.toInstant(),officialSavedAt);
    }
    public void validateImportDraft(LogisticsDraft draft) {
        securedImportDraft(draft);
    }
    @Transactional public String importDraft(LogisticsDraft draft) {
        LogisticsDraft securedDraft=securedImportDraft(draft);
        SecurityPrincipal principal=accessControl.require("BUSINESS_IMPORT",repository.regionsForDraft(securedDraft).iterator().next());
        LogisticsRecordView created=repository.insert(UUID.randomUUID().toString(),securedDraft,principal.subjectId(),clock.instant());
        audit(principal,created,"LOGISTICS_RECORD_IMPORTED");
        return persistValidated(created,principal).id();
    }
    @Transactional public LogisticsRecordView save(String id,long version,LogisticsDraft draft) {
        LogisticsRecordView existing=required(id); SecurityPrincipal principal=authorize("BUSINESS_UPDATE",repository.regionsForRecord(id)); requireVersion(existing,version);
        if (!existing.productCode().equals(draft.productCode())) throw invalid();
        if (existing.status()==LogisticsStatus.VOIDED) throw invalid();

        if (existing.status()==LogisticsStatus.APPROVED) com.cofco.qiqihar.graintrade.shared.application.OfficialSampleIdentityGuard.requireUnchanged(existing.values(),draft.values(),"LOG_");
        String reporter=existing.values().get("LOG_REPORTER");
        LogisticsDraft securedDraft=withReporter(draft,blank(reporter)?principal.displayName():reporter);
        validate(securedDraft); authorize("BUSINESS_UPDATE",repository.regionsForDraft(securedDraft)); authorize("BUSINESS_SUBMIT",repository.regionsForRecord(id)); authorize("BUSINESS_SUBMIT",repository.regionsForDraft(securedDraft)); LogisticsRecordView updated=repository.update(id,version,securedDraft,LogisticsStatus.APPROVED,null,principal.subjectId(),clock.instant()); repository.linkApprovedSamplePoint(id,principal.subjectId(),clock.instant()); audit(principal,updated,"LOGISTICS_RECORD_SAVED"); return authorizedView(updated);
    }
    private LogisticsRecordView persistValidated(LogisticsRecordView record, SecurityPrincipal principal) {
        authorize("BUSINESS_SUBMIT",repository.regionsForRecord(record.id()));
        LogisticsRecordView saved=repository.transition(record.id(),record.version(),LogisticsStatus.APPROVED,null,principal.subjectId(),clock.instant());
        repository.linkApprovedSamplePoint(saved.id(),principal.subjectId(),clock.instant());
        saved=required(saved.id());
        audit(principal,saved,"LOGISTICS_RECORD_SAVED");
        return authorizedView(saved);
    }
    @Transactional public LogisticsRecordView submit(String id,long version) {
        LogisticsRecordView existing=required(id);
        authorize("BUSINESS_SUBMIT",repository.regionsForRecord(id)); requireVersion(existing,version);
        if(existing.status()==LogisticsStatus.APPROVED)return authorizedView(existing);
        return save(id,version,storedDraft(existing));
    }

    private LogisticsDraft storedDraft(LogisticsRecordView existing) {
        Map<String,String> values=new java.util.LinkedHashMap<>();
        repository.definition(existing.productCode()).fields().stream()
            .filter(field -> !field.controlType().startsWith("READONLY"))
            .filter(field -> existing.values().get(field.code())!=null)
            .forEach(field -> values.put(field.code(),existing.values().get(field.code())));
        return new LogisticsDraft(existing.productCode(),values);
    }
    @Transactional public LogisticsRecordView approve(String id,long version) {
        throw new ClientRequestException("BUSINESS_REVIEW_REMOVED", "业务人工审核已取消，请校验后保存记录。");
    }
    @Transactional public LogisticsRecordView returned(String id,long version,String reason) {
        throw new ClientRequestException("BUSINESS_REVIEW_REMOVED", "业务人工审核已取消，请校验后保存记录。");
    }
    @Transactional public LogisticsRecordView voidRecord(String id,long version) {
        return transition(id,version,LogisticsStatus.VOIDED,null,"BUSINESS_UPDATE","LOGISTICS_RECORD_VOIDED");
    }
    private LogisticsRecordView transition(String id,long version,LogisticsStatus target,String reason,String permission,String auditAction) {
        LogisticsRecordView existing=required(id);
        Set<String> regions = repository.regionsForRecord(id);
        SecurityPrincipal principal;
        if (accessControl != null && target == LogisticsStatus.VOIDED) {
            if (regions.isEmpty()) throw invalid();
            principal = accessControl.requireBusinessVoid(regions.iterator().next());
            regions.forEach(accessControl::requireBusinessVoid);
        } else {
            principal = authorize(permission, regions);
        }
        requireVersion(existing,version);
        boolean allowed=(target==LogisticsStatus.PENDING_REVIEW
                && (existing.status()==LogisticsStatus.DRAFT || existing.status()==LogisticsStatus.RETURNED))
                || ((target==LogisticsStatus.APPROVED || target==LogisticsStatus.RETURNED) && existing.status()==LogisticsStatus.PENDING_REVIEW);
        allowed=allowed || (target==LogisticsStatus.VOIDED
                && existing.status()!=LogisticsStatus.VOIDED);
        String action=target==LogisticsStatus.PENDING_REVIEW?"SUBMIT"
                :target==LogisticsStatus.APPROVED?"APPROVE"
                :target==LogisticsStatus.RETURNED?"RETURN":"VOID";
        if(!allowed||!repository.actionAllowed(existing.productCode(),existing.status(),action)) throw invalid();
        if(separationOfDuties!=null && permission.equals("BUSINESS_APPROVE")) {
            separationOfDuties.requireIndependentApprover(
                    "LOGISTICS_RECORD",id,"LOGISTICS_RECORD_SUBMITTED",principal);
        }
        if(separationOfDuties!=null && permission.equals("BUSINESS_RETURN")) {
            separationOfDuties.requireIndependentReturner(
                    "LOGISTICS_RECORD",id,"LOGISTICS_RECORD_SUBMITTED",principal);
        }
        java.time.Instant transitionedAt=clock.instant();
        LogisticsRecordView updated=repository.transition(id,version,target,reason,principal.subjectId(),transitionedAt);
        if(target==LogisticsStatus.APPROVED){
            repository.linkApprovedSamplePoint(id,principal.subjectId(),transitionedAt);
            updated=required(id);
        }
        audit(principal,updated,auditAction); return authorizedView(updated);
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
    private AuthorizedReadScope readScope(){return accessControl==null?AuthorizedReadScope.unrestricted():accessControl.requireBusinessReadScope();}
    private void audit(SecurityPrincipal principal,LogisticsRecordView record,String action){
        if(audit==null)return;
        String regions=repository.regionsForRecord(record.id()).stream().sorted()
                .map(region->"\""+region+"\"").collect(java.util.stream.Collectors.joining(","));
        String surveyYear=record.values().get("surveyYear");
        if(blank(surveyYear))throw new IllegalStateException("Logistics survey year is missing");
        audit.record(principal,"LOGISTICS_RECORD",record.id(),action,clock.instant(),
                "{\"regionCodes\":["+regions+"],\"productCode\":\""+record.productCode()
                        +"\",\"surveyYear\":"+surveyYear+",\"version\":"+record.version()+"}");
    }
    private LogisticsRecordView required(String id){LogisticsRecordView value=repository.find(id); if(value==null) throw new ResourceNotFoundException("LOGISTICS_RECORD_NOT_FOUND","Logistics record was not found"); return value;}
    private LogisticsDraft securedImportDraft(LogisticsDraft draft) {
        Set<String> regions=repository.regionsForDraft(draft);
        SecurityPrincipal principal=authorize("BUSINESS_IMPORT",regions);
        LogisticsDraft secured=withReporter(draft,principal.displayName());
        validate(secured);
        if(!repository.actionAllowed(secured.productCode(),LogisticsStatus.DRAFT,"NEW"))throw invalid();
        return secured;
    }
    private static void requireVersion(LogisticsRecordView value,long version){if(version<0||value.version()!=version)throw new ConflictException("LOGISTICS_RECORD_VERSION_CONFLICT","Logistics record has changed");}
    private static LogisticsDraft withReporter(LogisticsDraft draft,String reporter){
        Map<String,String> values=new java.util.LinkedHashMap<>(draft.values());
        values.put("LOG_REPORTER",reporter);
        return new LogisticsDraft(draft.productCode(),values);
    }
    private LogisticsRecordView authorizedView(LogisticsRecordView record) {
        if(accessControl==null)return record;
        SecurityPrincipal principal=accessControl.authenticated().orElse(null);
        if(principal==null)return record;
        boolean regionAllowed=principal.isRootAdministrator() || repository.regionsForRecord(record.id()).stream().allMatch(principal::includesRegion);
        java.util.List<String> actions=record.allowedActions().stream().filter(action -> {
            if ("VOID".equals(action)) return !repository.regionsForRecord(record.id()).isEmpty()
                    && repository.regionsForRecord(record.id()).stream()
                            .allMatch(region -> accessControl.canVoidBusinessRecord(principal, region));
            String permission=switch(action){
                case "VIEW" -> "BUSINESS_READ";
                case "SAVE" -> "BUSINESS_UPDATE";
                case "SUBMIT" -> "BUSINESS_SUBMIT";
                case "APPROVE" -> "BUSINESS_APPROVE";
                case "RETURN" -> "BUSINESS_RETURN";
                case "VOID" -> "BUSINESS_UPDATE";
                default -> null;
            };
            if(permission==null||!principal.permits(permission))return false;
            if(!principal.hasSharedReportingScope(permission) && !regionAllowed)return false;
            if(separationOfDuties==null)return true;
            return switch(action){
                case "APPROVE" -> separationOfDuties.canApprove(
                        "LOGISTICS_RECORD",record.id(),"LOGISTICS_RECORD_SUBMITTED",principal);
                case "RETURN" -> separationOfDuties.canReturn(
                        "LOGISTICS_RECORD",record.id(),"LOGISTICS_RECORD_SUBMITTED",principal);
                default -> true;
            };
        }).toList();
        return new LogisticsRecordView(record.id(),record.productCode(),record.values(),record.displayValues(),
                record.status(),record.returnReason(),actions,record.version());
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String requiredLockedValue(FormalSampleIdentity identity,String code){
        String value=identity.lockedValues().path(code).asText(null);
        if(blank(value))throw invalid();
        return value;
    }
    private static String coordinate(String value,int scale){
        return new java.math.BigDecimal(value).setScale(scale,java.math.RoundingMode.HALF_UP).toPlainString();
    }
    private static ClientRequestException invalid(){return new ClientRequestException("INVALID_LOGISTICS_RECORD","Logistics record or query is invalid");}
}
