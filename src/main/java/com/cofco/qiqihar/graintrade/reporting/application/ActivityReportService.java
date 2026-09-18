package com.cofco.qiqihar.graintrade.reporting.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityReportService {
    private static final List<String> ACTION_ORDER = List.of(
            "CREATED", "UPDATED", "DELETED", "IMPORTED", "SUBMITTED", "RETURNED",
            "APPROVED", "EXPORTED", "ANNOTATED", "OTHER");
    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("CREATED", "新增"), Map.entry("UPDATED", "修改"),
            Map.entry("DELETED", "删除或退出使用"), Map.entry("IMPORTED", "导入"),
            Map.entry("SUBMITTED", "提交"), Map.entry("RETURNED", "退回"),
            Map.entry("APPROVED", "审核通过"), Map.entry("EXPORTED", "导出"),
            Map.entry("ANNOTATED", "地图标注"), Map.entry("OTHER", "其他留痕"));
    private final ActivityReportRepository repository;
    private final ActivityReportDocument document;
    private final AccessControl access;
    private final Clock clock;

    public ActivityReportService(ActivityReportRepository repository, ActivityReportDocument document,
            AccessControl access) {
        this.repository = repository;
        this.document = document;
        this.access = access;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public ActivityReport personal(int days) {
        validateDays(days);
        SecurityPrincipal principal = access.requireAuthenticated();
        Instant cutoff = clock.instant();
        return assemble("PERSONAL", days, cutoff, principal,
                repository.personal(principal.subjectId(), cutoff.minus(Duration.ofDays(days)), cutoff),
                "仅统计当前登录用户在周期内写入的不可变业务审计事件。", true);
    }

    @Transactional(readOnly = true)
    public ActivityReport system(int days) {
        validateDays(days);
        SecurityPrincipal principal = access.require("ACTIVITY_REPORT_SYSTEM", null);
        Instant cutoff = clock.instant();
        return assemble("SYSTEM", days, cutoff, principal,
                repository.system(cutoff.minus(Duration.ofDays(days)), cutoff),
                "仅统计当前有效账号在周期内写入的不可变业务审计事件；已停用或离职账号不计入。", false);
    }

    @Transactional
    public ActivityReportExport exportSystem(int days) {
        ActivityReport report = system(days);
        SecurityPrincipal principal = access.require("ACTIVITY_REPORT_SYSTEM", null);
        Instant generatedAt = clock.instant();
        byte[] bytes = document.create(report);
        String id = UUID.randomUUID().toString();
        String filename = "全系统周期使用总结-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneId.of("Asia/Shanghai")).format(generatedAt) + ".docx";
        String sha256 = sha256(bytes);
        repository.saveExport(new ActivityReportRepository.ExportRecord(
                id, days, report.periodStart(), report.periodEnd(), report.eventCutoff(),
                principal.subjectId(), generatedAt, filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sha256, bytes));
        return new ActivityReportExport(id, filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sha256, generatedAt);
    }

    @Transactional(readOnly = true)
    public ActivityReportExport.Content download(String exportId) {
        SecurityPrincipal principal = access.require("ACTIVITY_REPORT_SYSTEM", null);
        if (exportId == null || exportId.isBlank()) throw invalid();
        return repository.exportContent(exportId, principal.subjectId());
    }

    private static ActivityReport assemble(String kind, int days, Instant cutoff,
            SecurityPrincipal principal, ActivityReportRepository.Snapshot snapshot,
            String notice, boolean personal) {
        var actionCounts = new LinkedHashMap<String, Long>();
        ACTION_ORDER.forEach(code -> actionCounts.put(code, 0L));
        var domainCounts = new LinkedHashMap<String, Long>();
        var unitCounts = new LinkedHashMap<String, Long>();
        long total = 0;
        long sampleCreated = 0;
        long sampleDeleted = 0;
        for (var event : snapshot.events()) {
            String action = classify(event.actionCode());
            String domain = domain(event.aggregateType());
            actionCounts.merge(action, event.count(), Long::sum);
            domainCounts.merge(domain, event.count(), Long::sum);
            unitCounts.merge(event.workUnitCode() + "\u0000" + event.workUnitName(), event.count(), Long::sum);
            total += event.count();
            if (sampleAggregate(event.aggregateType()) && action.equals("CREATED")) sampleCreated += event.count();
            if (sampleAggregate(event.aggregateType()) && action.equals("DELETED")) sampleDeleted += event.count();
        }
        List<ActivityReport.Count> actions = actionCounts.entrySet().stream()
                .map(entry -> new ActivityReport.Count(entry.getKey(), ACTION_LABELS.get(entry.getKey()), entry.getValue()))
                .toList();
        List<ActivityReport.Count> domains = sortedCounts(domainCounts, ActivityReportService::domainLabel);
        List<ActivityReport.Count> units = unitCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new ActivityReport.Count(entry.getKey().split("\u0000", 2)[0],
                        entry.getKey().split("\u0000", 2)[1], entry.getValue())).toList();
        return new ActivityReport(kind, days, cutoff.minus(Duration.ofDays(days)), cutoff, cutoff,
                personal ? new ActivityReport.Subject(principal.subjectId(), snapshot.displayName(), snapshot.workUnitName()) : null,
                snapshot.effectiveUserCount(), total, sampleCreated, sampleDeleted, actions, domains, units, notice);
    }

    private static List<ActivityReport.Count> sortedCounts(Map<String, Long> values,
            java.util.function.Function<String, String> labeler) {
        var result = new ArrayList<ActivityReport.Count>();
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> result.add(new ActivityReport.Count(
                        entry.getKey(), labeler.apply(entry.getKey()), entry.getValue())));
        return result;
    }

    static String classify(String value) {
        String action = value.toUpperCase(Locale.ROOT);
        if (contains(action, "DELETE", "DELETED", "RETIRE", "VOID", "REMOVED")) return "DELETED";
        if (action.contains("IMPORT")) return "IMPORTED";
        if (action.contains("SUBMIT")) return "SUBMITTED";
        if (contains(action, "RETURN", "REJECT")) return "RETURNED";
        if (contains(action, "APPROV", "REVIEWED", "PUBLISH")) return "APPROVED";
        if (action.contains("EXPORT")) return "EXPORTED";
        if (action.contains("ANNOTATION")) return "ANNOTATED";
        if (contains(action, "CREATE", "CREATED", "REGISTER")) return "CREATED";
        if (contains(action, "UPDATE", "UPDATED", "CORRECT", "EDIT", "ASSIGN", "LINK", "MERGE")) return "UPDATED";
        return "OTHER";
    }

    private static boolean contains(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static boolean sampleAggregate(String value) {
        String aggregate = value.toUpperCase(Locale.ROOT);
        return aggregate.contains("SAMPLE_POINT") || aggregate.contains("DESIGN_SAMPLE");
    }

    private static String domain(String value) {
        String aggregate = value.toUpperCase(Locale.ROOT);
        if (aggregate.contains("SAMPLE")) return "SAMPLE_NETWORK";
        if (aggregate.contains("PRODUCTION")) return "PRODUCTION";
        if (aggregate.contains("MARKET")) return "MARKET";
        if (aggregate.contains("LOGISTICS")) return "LOGISTICS";
        if (aggregate.contains("SUPPLY")) return "SUPPLY";
        if (aggregate.contains("REPORT")) return "REPORTING";
        if (aggregate.contains("ANNOTATION")) return "MAP_ANNOTATION";
        if (aggregate.contains("IDENTITY") || aggregate.contains("USER")) return "IDENTITY";
        return "OTHER";
    }

    private static String domainLabel(String code) {
        return switch (code) {
            case "SAMPLE_NETWORK" -> "样本网络";
            case "PRODUCTION" -> "产情监测";
            case "MARKET" -> "市场监测";
            case "LOGISTICS" -> "物流监测";
            case "SUPPLY" -> "供需分析";
            case "REPORTING" -> "报表中心";
            case "MAP_ANNOTATION" -> "地图标注";
            case "IDENTITY" -> "身份与权限";
            default -> "其他业务";
        };
    }

    private static void validateDays(int days) {
        if (days != 7 && days != 30) throw invalid();
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException("INVALID_ACTIVITY_REPORT_REQUEST", "周期报告仅支持最近7天或30天");
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
