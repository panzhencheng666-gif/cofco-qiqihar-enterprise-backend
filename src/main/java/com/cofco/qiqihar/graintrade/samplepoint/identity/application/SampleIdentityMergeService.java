package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.EXPORT_CREATED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.EXPORT_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.JOB_COMPLETED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.JOB_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.REQUEST_APPLIED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.REQUEST_APPROVAL_AUTHORIZED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.REQUEST_REJECTED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.REQUEST_SUBMITTED;
import static com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.REQUEST_TYPE;

import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityGovernanceWorkbook.Row;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.ExportFile;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.ExportSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.JobSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.JobView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.RequestSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.ReviewView;
import com.cofco.qiqihar.graintrade.samplepoint.identity.application.SampleIdentityMergeView.RowResult;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.CandidateRecord;
import com.cofco.qiqihar.graintrade.samplepoint.identity.infrastructure.JdbcSampleIdentityMergeRepository.DecisionRecord;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SampleIdentityMergeService {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private final JdbcSampleIdentityMergeRepository repository;
    private final AccessControl access;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SampleIdentityMergeService(
            JdbcSampleIdentityMergeRepository repository, AccessControl access,
            SeparationOfDutiesPolicy separationOfDuties, BusinessAuditRecorder audit,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.separationOfDuties = separationOfDuties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ExportFile export() {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        List<CandidateRecord> candidates = repository.findHistoricalDuplicates();
        Map<String, List<CandidateRecord>> groups = candidates.stream().collect(Collectors.groupingBy(
                SampleIdentityMergeService::candidateGroupKey, LinkedHashMap::new, Collectors.toList()));
        List<CandidateRecord> authorized = groups.values().stream()
                .filter(group -> group.stream().allMatch(
                        candidate -> principal.includesRegion(candidate.regionCode())))
                .flatMap(List::stream).toList();
        if (authorized.isEmpty()) {
            throw new ClientRequestException("SAMPLE_IDENTITY_DUPLICATES_EMPTY",
                    "当前账号授权范围内没有可导出的重复样本身份");
        }
        UUID batchId = UUID.randomUUID();
        List<Row> rows = authorized.stream().map(candidate -> exportRow(batchId, candidate)).toList();
        Instant now = clock.instant();
        ExportSnapshot snapshot = new ExportSnapshot(
                batchId, principal.subjectId(), principal.workUnitCode(), now, rows);
        audit.record(principal, EXPORT_TYPE, batchId.toString(), EXPORT_CREATED, now, json(snapshot));
        return new ExportFile("历史重复样本身份安全治理包-" + batchId + ".xlsx",
                SampleIdentityGovernanceWorkbook.create(batchId, rows), batchId, rows.size());
    }

    @Transactional
    public JobView upload(
            String idempotencyKey, String filename, String mediaType, byte[] bytes) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        validateUpload(idempotencyKey, filename, mediaType, bytes);
        String contentSha = digest(bytes);
        repository.lockIdempotency(principal.subjectId(), idempotencyKey);
        var existing = repository.findJobByIdempotency(
                principal.subjectId(), principal.workUnitCode(), idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().contentSha256().equals(contentSha)) {
                throw new ConflictException("SAMPLE_IDENTITY_MERGE_IDEMPOTENCY_CONFLICT",
                        "相同上传标识已用于其他身份治理文件");
            }
            return existing.get().view();
        }
        SampleIdentityGovernanceWorkbook.ParsedWorkbook workbook;
        try {
            workbook = SampleIdentityGovernanceWorkbook.read(bytes);
        } catch (RuntimeException exception) {
            throw invalidWorkbook();
        }
        ExportSnapshot exported = repository.findExport(
                        workbook.batchId(), principal.subjectId(), principal.workUnitCode())
                .orElseThrow(() -> new ConflictException("SAMPLE_IDENTITY_MERGE_EXPORT_NOT_OWNED",
                        "导出批次不存在、已失效或不属于当前账号"));
        requireImmutableSnapshot(exported.rows(), workbook.rows());
        return process(principal, idempotencyKey, contentSha, exported, workbook.rows());
    }

    @Transactional(readOnly = true)
    public List<JobView> history() {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return repository.history(principal.subjectId(), principal.workUnitCode()).stream()
                .map(JobSnapshot::view).toList();
    }

    @Transactional(readOnly = true)
    public JobView status(UUID jobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return repository.findJob(jobId, principal.subjectId(), principal.workUnitCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SAMPLE_IDENTITY_MERGE_JOB_NOT_FOUND", "身份治理任务不存在"))
                .view();
    }

    @Transactional(readOnly = true)
    public List<ReviewView> reviewQueue() {
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", null);
        return repository.pendingRequests(principal.workUnitCode()).stream()
                .filter(request -> principal.includesRegion(request.regionCode()))
                .map(request -> reviewView(request, "PENDING_REVIEW", null))
                .toList();
    }

    @Transactional
    public ReviewView review(UUID requestId, String decisionValue, String reason) {
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", null);
        RequestSnapshot request = repository.findRequest(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SAMPLE_IDENTITY_MERGE_REQUEST_NOT_FOUND", "身份归并申请不存在"));
        access.require("BUSINESS_APPROVE", request.regionCode());
        if (!principal.workUnitCode().equals(request.workUnitCode())) {
            throw new ConflictException("SAMPLE_IDENTITY_MERGE_WORK_UNIT_MISMATCH",
                    "身份归并申请不属于当前单位");
        }
        String decision = decisionValue == null
                ? "" : decisionValue.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT").contains(decision)
                || reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new ClientRequestException("INVALID_SAMPLE_IDENTITY_MERGE_REVIEW",
                    "请选择通过或驳回，并填写明确的审核依据");
        }
        repository.lockRequest(requestId);
        var existing = repository.decision(requestId);
        if (existing.isPresent()) {
            if (matchesDecision(existing.get(), decision)) {
                return reviewView(request, status(existing.get()), existing.get());
            }
            throw new ConflictException("SAMPLE_IDENTITY_MERGE_ALREADY_REVIEWED",
                    "该身份归并申请已完成其他审核结论");
        }
        boolean mayReview = "REJECT".equals(decision)
                ? separationOfDuties.canReturn(
                        REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED, principal)
                : separationOfDuties.canApprove(
                        REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED, principal);
        if (!mayReview) {
            throw new ConflictException("SAMPLE_IDENTITY_MERGE_SELF_REVIEW_FORBIDDEN",
                    "身份归并必须由另一名审核人完成；平台唯一所有者可按特权规则自审");
        }
        boolean privilegedSelfReview = principal.subjectId().equals(request.requestedBy());
        Instant now = clock.instant();
        if ("REJECT".equals(decision)) {
            Map<String, Object> detail = decisionDetail(
                    request, reason.trim(), null, privilegedSelfReview);
            audit.record(principal, request.workUnitCode(), REQUEST_TYPE, requestId.toString(),
                    REQUEST_REJECTED, now, json(detail));
            return new ReviewView(requestId, request.sourceDomain(), request.sourceRecordId(),
                    request.currentSamplePointId(), request.targetSamplePointId(), request.regionCode(),
                    request.reviewBasis(), request.requestedBy(), "REJECTED", principal.subjectId(),
                    reason.trim(), now, null, privilegedSelfReview);
        }
        if (!repository.matchesCurrent(request)) {
            throw new ConflictException("SAMPLE_IDENTITY_MERGE_STALE",
                    "业务记录或样本点身份已发生变化，请重新导出治理清单");
        }
        String inputDigest = digest(json(request).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> detail = decisionDetail(
                request, reason.trim(), null, privilegedSelfReview);
        audit.record(principal, request.workUnitCode(), REQUEST_TYPE, requestId.toString(),
                REQUEST_APPROVAL_AUTHORIZED, now, json(detail));
        UUID resolutionBatchId = repository.stageAndApply(request, inputDigest);
        detail = decisionDetail(request, reason.trim(), resolutionBatchId, privilegedSelfReview);
        audit.record(principal, request.workUnitCode(), REQUEST_TYPE, requestId.toString(),
                REQUEST_APPLIED, now, json(detail));
        return new ReviewView(requestId, request.sourceDomain(), request.sourceRecordId(),
                request.currentSamplePointId(), request.targetSamplePointId(), request.regionCode(),
                request.reviewBasis(), request.requestedBy(), "APPLIED", principal.subjectId(),
                reason.trim(), now, resolutionBatchId, privilegedSelfReview);
    }

    private JobView process(
            SecurityPrincipal principal, String idempotencyKey, String contentSha,
            ExportSnapshot exported, List<Row> rows) {
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();
        Map<String, Set<UUID>> groupMembers = exported.rows().stream().collect(Collectors.groupingBy(
                Row::duplicateIdentityGroup, Collectors.mapping(
                        Row::currentSamplePointId, Collectors.toSet())));
        ArrayList<RowResult> results = new ArrayList<>();
        int accepted = 0;
        int pending = 0;
        int skipped = 0;
        int failed = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.action().isBlank() || SampleIdentityGovernanceWorkbook.DEFER.equals(row.action())
                    || SampleIdentityGovernanceWorkbook.KEEP_DISTINCT.equals(row.action())) {
                skipped++;
                String message = row.action().isBlank() ? "未选择处理方式，保持现状"
                        : SampleIdentityGovernanceWorkbook.DEFER.equals(row.action())
                                ? "证据不足，暂不处理" : "已记录为不同身份，保持现状";
                results.add(new RowResult(index + 2, row.sourceRecordId(), "NO_CHANGE", message));
                continue;
            }
            Set<UUID> members = groupMembers.getOrDefault(
                    row.duplicateIdentityGroup(), Set.of());
            if (!SampleIdentityGovernanceWorkbook.MERGE.equals(row.action())
                    || row.targetSamplePointId() == null
                    || row.targetSamplePointId().equals(row.currentSamplePointId())
                    || !members.contains(row.targetSamplePointId())) {
                failed++;
                results.add(new RowResult(index + 2, row.sourceRecordId(), "ERROR",
                        "规范样本点必须是同一重复身份组内的另一个有效样本点"));
                continue;
            }
            UUID requestId = UUID.randomUUID();
            RequestSnapshot request = new RequestSnapshot(
                    requestId, jobId, exported.batchId(), row.sourceDomain(), row.sourceRecordId(),
                    row.sourceVersion(), row.currentSamplePointId(), row.targetSamplePointId(),
                    stableSubjectId(row.sourceDomain(), row.targetSamplePointId()), row.regionCode(),
                    row.longitude(), row.latitude(), row.duplicateIdentityGroup(),
                    principal.subjectId(), principal.workUnitCode(), row.reviewBasis(), now);
            audit.record(principal, REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED,
                    now, json(request));
            accepted++;
            pending++;
            results.add(new RowResult(index + 2, row.sourceRecordId(), "PENDING_REVIEW",
                    "已提交独立审核"));
        }
        String status = failed > 0 ? "COMPLETED_WITH_ERRORS"
                : pending > 0 ? "PENDING_REVIEW" : "COMPLETED";
        JobView view = new JobView(jobId, exported.batchId(), status, accepted, pending,
                skipped, failed, idempotencyKey, now, results);
        JobSnapshot snapshot = new JobSnapshot(
                view, contentSha, principal.subjectId(), principal.workUnitCode());
        audit.record(principal, JOB_TYPE, jobId.toString(), JOB_COMPLETED, now, json(snapshot));
        return view;
    }

    private Row exportRow(UUID batchId, CandidateRecord candidate) {
        String group = digest(("IDENTITY_GROUP|" + candidateGroupKey(candidate))
                .getBytes(StandardCharsets.UTF_8));
        String binding = digest((batchId + "|" + candidate.sourceDomain() + "|"
                + candidate.sourceRecordId() + "|" + candidate.sourceVersion() + "|"
                + candidate.currentSamplePointId() + "|" + candidate.regionCode() + "|"
                + coordinateKey(candidate)).getBytes(StandardCharsets.UTF_8));
        return new Row(candidate.sourceRecordId(), candidate.sourceVersion(),
                candidate.sourceDomain(), candidate.productCode(), candidate.surveyPeriod(),
                candidate.currentSamplePointId(), candidate.sampleName(), candidate.sampleContact(),
                candidate.regionCode(), candidate.regionName(), candidate.longitude(),
                candidate.latitude(), candidate.approvedRecordCount(), group, binding, "",
                candidate.canonicalSamplePointId(), "", "");
    }

    private static void requireImmutableSnapshot(List<Row> expected, List<Row> actual) {
        Map<String, Row> expectedById = expected.stream().collect(Collectors.toMap(
                Row::sourceRecordId, Function.identity()));
        if (actual.size() != expected.size()
                || !expectedById.keySet().equals(actual.stream().map(
                        Row::sourceRecordId).collect(Collectors.toSet()))) {
            throw invalidWorkbook();
        }
        for (Row row : actual) {
            Row source = expectedById.get(row.sourceRecordId());
            if (source.sourceVersion() != row.sourceVersion()
                    || !source.sourceDomain().equals(row.sourceDomain())
                    || !source.productCode().equals(row.productCode())
                    || !source.surveyPeriod().equals(row.surveyPeriod())
                    || !source.currentSamplePointId().equals(row.currentSamplePointId())
                    || !source.sampleName().equals(row.sampleName())
                    || !source.sampleContact().equals(row.sampleContact())
                    || !source.regionCode().equals(row.regionCode())
                    || !source.regionName().equals(row.regionName())
                    || source.longitude().compareTo(row.longitude()) != 0
                    || source.latitude().compareTo(row.latitude()) != 0
                    || source.approvedRecordCount() != row.approvedRecordCount()
                    || !source.duplicateIdentityGroup().equals(row.duplicateIdentityGroup())
                    || !source.rowBinding().equals(row.rowBinding())) {
                throw invalidWorkbook();
            }
        }
    }

    private void validateUpload(String key, String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128 || filename == null
                || filename.isBlank() || filename.length() > 255
                || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ClientRequestException("INVALID_SAMPLE_IDENTITY_MERGE_UPLOAD",
                    "历史样本身份治理文件上传请求无效");
        }
    }

    private static String candidateGroupKey(CandidateRecord candidate) {
        return candidate.sourceDomain() + "|" + normalize(candidate.sampleName()) + "|"
                + normalizeContact(candidate.sampleContact()) + "|" + candidate.regionCode()
                + "|" + coordinateKey(candidate);
    }

    private static String coordinateKey(CandidateRecord candidate) {
        return candidate.longitude().stripTrailingZeros().toPlainString() + "|"
                + candidate.latitude().stripTrailingZeros().toPlainString();
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeContact(String value) {
        return normalize(value).replaceAll("[()（）-]+", "");
    }

    private static String stableSubjectId(String domain, UUID target) {
        return "GOVERNED_SAMPLE:" + domain + ":" + target;
    }

    private static boolean matchesDecision(DecisionRecord existing, String decision) {
        return ("APPROVE".equals(decision) && REQUEST_APPLIED.equals(existing.actionCode()))
                || ("REJECT".equals(decision) && REQUEST_REJECTED.equals(existing.actionCode()));
    }

    private static String status(DecisionRecord decision) {
        return REQUEST_APPLIED.equals(decision.actionCode()) ? "APPLIED" : "REJECTED";
    }

    private static ReviewView reviewView(
            RequestSnapshot request, String status, DecisionRecord decision) {
        return new ReviewView(request.requestId(), request.sourceDomain(), request.sourceRecordId(),
                request.currentSamplePointId(), request.targetSamplePointId(), request.regionCode(),
                request.reviewBasis(), request.requestedBy(), status,
                decision == null ? null : decision.actor(),
                decision == null ? null : decision.reason(),
                decision == null ? null : decision.occurredAt(),
                decision == null ? null : decision.resolutionBatchId(),
                decision != null && decision.privilegedSelfReview());
    }

    private static Map<String, Object> decisionDetail(
            RequestSnapshot request, String reason, UUID resolutionBatchId,
            boolean privilegedSelfReview) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", request.requestId());
        detail.put("sourceDomain", request.sourceDomain());
        detail.put("sourceRecordId", request.sourceRecordId());
        detail.put("currentSamplePointId", request.currentSamplePointId());
        detail.put("targetSamplePointId", request.targetSamplePointId());
        detail.put("regionCode", request.regionCode());
        detail.put("requestedBy", request.requestedBy());
        detail.put("reason", reason);
        if (resolutionBatchId != null) detail.put("resolutionBatchId", resolutionBatchId);
        detail.put("privilegedSelfReview", privilegedSelfReview);
        return detail;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SAMPLE_IDENTITY_MERGE_AUDIT_SERIALIZATION_FAILED", exception);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ClientRequestException invalidWorkbook() {
        return new ClientRequestException("INVALID_SAMPLE_IDENTITY_GOVERNANCE_WORKBOOK",
                "治理表的只读字段、行绑定或导出范围已被修改");
    }
}
