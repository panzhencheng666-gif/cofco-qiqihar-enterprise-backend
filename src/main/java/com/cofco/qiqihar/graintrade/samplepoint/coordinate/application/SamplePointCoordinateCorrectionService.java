package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.EXPORT_CREATED;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.EXPORT_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.JOB_COMPLETED;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.JOB_TYPE;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.REQUEST_APPLIED;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.REQUEST_REJECTED;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.REQUEST_SUBMITTED;
import static com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository.REQUEST_TYPE;

import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.Candidate;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.ExportFile;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.ExportSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.JobSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.JobView;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.RequestSnapshot;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.ReviewView;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionView.RowResult;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.infrastructure.JdbcSamplePointCoordinateCorrectionRepository;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SamplePointCoordinateCorrectionService {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final Set<String> COORDINATE_SOURCES = Set.of(
            "FIELD_GPS", "EVIDENCE_PHOTO", "OFFICIAL_GEOCODE", "VERIFIED_MAP", "OTHER");
    private final JdbcSamplePointCoordinateCorrectionRepository repository;
    private final SamplePointCoordinateGuard coordinateGuard;
    private final AccessControl access;
    private final SeparationOfDutiesPolicy separationOfDuties;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SamplePointCoordinateCorrectionService(
            JdbcSamplePointCoordinateCorrectionRepository repository,
            SamplePointCoordinateGuard coordinateGuard, AccessControl access,
            SeparationOfDutiesPolicy separationOfDuties, BusinessAuditRecorder audit,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.coordinateGuard = coordinateGuard;
        this.access = access;
        this.separationOfDuties = separationOfDuties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ExportFile export() {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        List<Candidate> all = repository.findGlobalDuplicates();
        Map<String, List<Candidate>> groups = all.stream().collect(Collectors.groupingBy(
                candidate -> key(candidate.longitude(), candidate.latitude()),
                LinkedHashMap::new, Collectors.toList()));
        List<Candidate> authorized = groups.values().stream()
                .filter(group -> group.stream().allMatch(
                        candidate -> principal.includesRegion(candidate.regionCode())))
                .flatMap(List::stream).toList();
        if (authorized.isEmpty()) {
            throw new ClientRequestException("SAMPLE_POINT_COORDINATE_DUPLICATES_EMPTY",
                    "当前账号授权范围内没有可导出的重复坐标样本点");
        }
        UUID batchId = UUID.randomUUID();
        List<SamplePointCoordinateCorrectionWorkbook.Row> rows = authorized.stream()
                .map(candidate -> exportRow(batchId, candidate)).toList();
        Instant now = clock.instant();
        ExportSnapshot snapshot = new ExportSnapshot(
                batchId, principal.subjectId(), principal.workUnitCode(), now, rows);
        audit.record(principal, EXPORT_TYPE, batchId.toString(), EXPORT_CREATED,
                now, json(snapshot));
        byte[] bytes = SamplePointCoordinateCorrectionWorkbook.create(batchId, rows);
        return new ExportFile("总揽监测全局重复坐标样本点安全修正包-" + batchId + ".xlsx",
                bytes, batchId, rows.size());
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
                throw new ConflictException("SAMPLE_POINT_CORRECTION_IDEMPOTENCY_CONFLICT",
                        "相同上传标识已用于其他修正文件");
            }
            return existing.get().view();
        }
        SamplePointCoordinateCorrectionWorkbook.ParsedWorkbook workbook;
        try {
            workbook = SamplePointCoordinateCorrectionWorkbook.read(bytes);
        } catch (RuntimeException exception) {
            throw new ClientRequestException("INVALID_SAMPLE_POINT_CORRECTION_WORKBOOK",
                    "样本点坐标修正表格式或绑定信息无效");
        }
        ExportSnapshot export = repository.findExport(
                        workbook.batchId(), principal.subjectId(), principal.workUnitCode())
                .orElseThrow(() -> new ConflictException("SAMPLE_POINT_CORRECTION_EXPORT_NOT_OWNED",
                        "导出批次不存在、已失效或不属于当前账号"));
        requireImmutableSnapshot(export.rows(), workbook.rows());
        return process(principal, idempotencyKey, contentSha, export,
                workbook.rows(), null);
    }

    public List<JobView> history() {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return repository.history(principal.subjectId(), principal.workUnitCode())
                .stream().map(JobSnapshot::view).toList();
    }

    public JobView status(UUID jobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return ownedJob(jobId, principal).view();
    }

    @Transactional
    public JobView retry(UUID jobId, String idempotencyKey) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw invalidUpload();
        }
        JobSnapshot previous = ownedJob(jobId, principal);
        if (previous.view().failedRows() == 0) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_RETRY_NOT_AVAILABLE",
                    "该修正任务没有可重试的失败行");
        }
        repository.lockIdempotency(principal.subjectId(), idempotencyKey);
        var existing = repository.findJobByIdempotency(
                principal.subjectId(), principal.workUnitCode(), idempotencyKey);
        if (existing.isPresent()) return existing.get().view();
        ExportSnapshot export = repository.findExport(previous.view().batchId(),
                        principal.subjectId(), principal.workUnitCode())
                .orElseThrow(() -> new ConflictException("SAMPLE_POINT_CORRECTION_EXPORT_NOT_OWNED",
                        "原导出批次已不可用"));
        return process(principal, idempotencyKey, previous.contentSha256(), export,
                previous.submittedRows(), jobId);
    }

    public ErrorFile errors(UUID jobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        JobView job = ownedJob(jobId, principal).view();
        StringBuilder csv = new StringBuilder("\uFEFF工作表行号,样本点编号,错误代码,失败原因\n");
        job.rowResults().stream().filter(result -> "ERROR".equals(result.outcomeCode()))
                .forEach(result -> csv.append(result.rowNumber()).append(',')
                        .append(result.samplePointId()).append(',')
                        .append(csv(result.errorCode())).append(',')
                        .append(csv(result.message())).append('\n'));
        return new ErrorFile("样本点坐标修正失败明细-" + jobId + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    public List<ReviewView> reviewQueue() {
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", null);
        return repository.pendingRequests(principal.workUnitCode()).stream()
                .filter(request -> principal.includesRegion(request.regionCode()))
                .map(request -> reviewView(request, "PENDING_REVIEW", null, null, null))
                .toList();
    }

    @Transactional
    public ReviewView submit(
            String idempotencyKey, FormalSampleCoordinateChangeCommand command) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        validateDirectRequest(idempotencyKey, command);
        String requestSha256 = digest(canonicalRequest(command).getBytes(StandardCharsets.UTF_8));
        repository.lockIdempotency(principal.subjectId(), idempotencyKey);
        var existing = repository.findRequestByIdempotency(
                principal.subjectId(), principal.workUnitCode(), idempotencyKey);
        if (existing.isPresent()) {
            if (!requestSha256.equals(existing.get().requestSha256())) {
                throw new ConflictException("SAMPLE_POINT_CORRECTION_IDEMPOTENCY_CONFLICT",
                        "相同提交标识已用于其他坐标变更申请");
            }
            return reviewView(existing.get(), "PENDING_REVIEW", null, null, null);
        }

        Candidate candidate = repository.lockApprovedCandidate(command.samplePointId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "FORMAL_SAMPLE_COORDINATE_CHANGE_NOT_AVAILABLE",
                        "正式样本不存在或当前状态不可申请坐标变更"));
        access.require("BUSINESS_IMPORT", candidate.regionCode());
        if (candidate.version() != command.expectedVersion()
                || candidate.longitude().compareTo(command.originalLongitude()) != 0
                || candidate.latitude().compareTo(command.originalLatitude()) != 0) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_STALE",
                    "样本点版本或当前坐标已变化，请刷新后重新申请");
        }
        if (candidate.longitude().compareTo(command.correctedLongitude()) == 0
                && candidate.latitude().compareTo(command.correctedLatitude()) == 0) {
            throw new ClientRequestException("SAMPLE_POINT_COORDINATE_UNCHANGED",
                    "变更后坐标与当前坐标相同");
        }
        if (!repository.withinRegion(candidate.regionCode(),
                command.correctedLongitude(), command.correctedLatitude())) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_OUTSIDE_REGION",
                    "变更后坐标不在样本点所属行政边界内");
        }
        coordinateGuard.lockAndRequireAvailable(candidate.samplePointId(),
                command.correctedLongitude(), command.correctedLatitude());

        Instant now = clock.instant();
        UUID requestId = UUID.randomUUID();
        RequestSnapshot request = new RequestSnapshot(
                requestId, null, null, candidate.samplePointId(), candidate.version(),
                candidate.canonicalName(), candidate.regionCode(), candidate.regionName(),
                candidate.longitude(), candidate.latitude(), command.correctedLongitude(),
                command.correctedLatitude(), command.coordinateSource().trim(),
                command.changeReason().trim(), principal.subjectId(), principal.workUnitCode(), now,
                command.coordinateCollectedAt(), command.verifiedAddress().trim(),
                command.changeReason().trim(), command.evidenceReference().trim(),
                idempotencyKey, requestSha256);
        audit.record(principal, REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED,
                now, json(request));
        return reviewView(request, "PENDING_REVIEW", null, null, null);
    }

    @Transactional
    public ReviewView review(UUID requestId, String decision, String reason) {
        SecurityPrincipal principal = access.require("BUSINESS_APPROVE", null);
        RequestSnapshot request = repository.findRequest(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SAMPLE_POINT_CORRECTION_REQUEST_NOT_FOUND", "坐标修正申请不存在"));
        access.require("BUSINESS_APPROVE", request.regionCode());
        if (!principal.workUnitCode().equals(request.workUnitCode())) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_WORK_UNIT_MISMATCH",
                    "修正申请不属于当前单位");
        }
        if (!("APPROVE".equals(decision) || "REJECT".equals(decision))) {
            throw new ClientRequestException("INVALID_SAMPLE_POINT_CORRECTION_REVIEW",
                    "审核决定必须为通过或驳回");
        }
        boolean mayReview = "REJECT".equals(decision)
                ? separationOfDuties.canReturn(
                        REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED, principal)
                : separationOfDuties.canApprove(
                        REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED, principal);
        if (!mayReview) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_SELF_REVIEW_FORBIDDEN",
                    "坐标修正必须由另一名审核人独立审核");
        }
        boolean privilegedSelfReview = principal.subjectId().equals(request.requestedBy());
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new ClientRequestException("INVALID_SAMPLE_POINT_CORRECTION_REVIEW",
                    "请填写明确的审核依据");
        }
        repository.lockRequest(requestId);
        if (repository.hasDecision(requestId)) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_ALREADY_REVIEWED",
                    "该坐标修正申请已完成审核");
        }
        Instant now = clock.instant();
        if ("REJECT".equals(decision)) {
            audit.record(principal, request.workUnitCode(), REQUEST_TYPE, requestId.toString(),
                    REQUEST_REJECTED, now, json(Map.of(
                            "requestId", requestId, "regionCode", request.regionCode(),
                            "reason", reason.trim(), "requestedBy", request.requestedBy(),
                            "privilegedSelfReview", privilegedSelfReview)));
            return reviewView(request, "REJECTED", principal.subjectId(), reason.trim(), now);
        }
        if (!repository.matchesCurrent(request)) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_STALE",
                    "样本点已发生变化，请重新导出修正清单");
        }
        if (!repository.withinRegion(request.regionCode(),
                request.correctedLongitude(), request.correctedLatitude())) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_OUTSIDE_REGION",
                    "修正后坐标不在样本点所属地区范围内");
        }
        coordinateGuard.lockAndRequireAvailable(request.samplePointId(),
                request.correctedLongitude(), request.correctedLatitude());
        if (repository.apply(request, principal.subjectId(), now) != 1) {
            throw new ConflictException("SAMPLE_POINT_CORRECTION_STALE",
                    "样本点已发生变化，请重新导出修正清单");
        }
        Map<String, Object> appliedDetail = new LinkedHashMap<>();
        appliedDetail.put("requestId", requestId);
        appliedDetail.put("samplePointId", request.samplePointId());
        appliedDetail.put("regionCode", request.regionCode());
        appliedDetail.put("requestedBy", request.requestedBy());
        appliedDetail.put("reason", reason.trim());
        appliedDetail.put("longitude", request.correctedLongitude());
        appliedDetail.put("latitude", request.correctedLatitude());
        appliedDetail.put("coordinateSource", request.coordinateSource());
        appliedDetail.put("coordinateCollectedAt", request.coordinateCollectedAt());
        appliedDetail.put("verifiedAddress", request.verifiedAddress());
        appliedDetail.put("changeReason", request.changeReason());
        appliedDetail.put("evidenceReference", request.evidenceReference());
        appliedDetail.put("requestSha256", request.requestSha256());
        appliedDetail.put("privilegedSelfReview", privilegedSelfReview);
        audit.record(principal, request.workUnitCode(), REQUEST_TYPE, requestId.toString(),
                REQUEST_APPLIED, now, json(appliedDetail));
        return reviewView(request, "APPLIED", principal.subjectId(), reason.trim(), now);
    }

    private JobView process(
            SecurityPrincipal principal, String idempotencyKey, String contentSha,
            ExportSnapshot export, List<SamplePointCoordinateCorrectionWorkbook.Row> rows,
            UUID retryOf) {
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();
        Map<String, List<SamplePointCoordinateCorrectionWorkbook.Row>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        SamplePointCoordinateCorrectionWorkbook.Row::duplicateGroupId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<UUID, String> errors = new HashMap<>();
        groups.values().forEach(group -> validateGroup(group, errors));
        validateFinalCoordinates(rows, errors);
        for (var row : rows) {
            if (!repository.matchesCurrent(row.samplePointId(), row.expectedVersion(),
                    row.originalLongitude(), row.originalLatitude())) {
                errors.put(row.samplePointId(), "样本点版本或原坐标已变化，请重新导出修正清单");
            }
            if (SamplePointCoordinateCorrectionWorkbook.CHANGE.equals(row.action())
                    && row.correctedLongitude() != null && row.correctedLatitude() != null) {
                if (!repository.withinRegion(
                        row.regionCode(), row.correctedLongitude(), row.correctedLatitude())) {
                    errors.put(row.samplePointId(), "修正后坐标不在样本点所属地区范围内");
                } else {
                    try {
                        coordinateGuard.lockAndRequireAvailable(row.samplePointId(),
                                row.correctedLongitude(), row.correctedLatitude());
                    } catch (ConflictException exception) {
                        errors.put(row.samplePointId(), exception.clientMessage());
                    }
                }
            }
        }
        propagateGroupErrors(groups, errors);
        ArrayList<RowResult> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            String error = errors.get(row.samplePointId());
            if (error != null) {
                results.add(new RowResult(index + 2, row.samplePointId(), "ERROR",
                        "SAMPLE_POINT_CORRECTION_ROW_INVALID", error, null));
            } else if (SamplePointCoordinateCorrectionWorkbook.KEEP.equals(row.action())) {
                results.add(new RowResult(index + 2, row.samplePointId(), "NO_CHANGE",
                        null, "保留原坐标，等待同组其他样本点完成修正", null));
            } else {
                UUID requestId = UUID.randomUUID();
                RequestSnapshot request = new RequestSnapshot(
                        requestId, jobId, export.batchId(), row.samplePointId(),
                        row.expectedVersion(), row.canonicalName(), row.regionCode(),
                        row.regionName(),
                        row.originalLongitude(), row.originalLatitude(), row.correctedLongitude(),
                        row.correctedLatitude(), row.coordinateSource(), row.correctionNote(),
                        principal.subjectId(), principal.workUnitCode(), now, null, null,
                        row.correctionNote(), null, null, null);
                audit.record(principal, REQUEST_TYPE, requestId.toString(), REQUEST_SUBMITTED,
                        now, json(request));
                results.add(new RowResult(index + 2, row.samplePointId(), "PENDING_REVIEW",
                        null, "已提交独立审核", requestId));
            }
        }
        int failed = (int) results.stream().filter(value -> "ERROR".equals(value.outcomeCode())).count();
        int pending = (int) results.stream().filter(
                value -> "PENDING_REVIEW".equals(value.outcomeCode())).count();
        String status = failed > 0 ? "COMPLETED_WITH_ERRORS"
                : pending > 0 ? "PENDING_REVIEW" : "COMPLETED";
        JobView view = new JobView(jobId, export.batchId(), principal.subjectId(),
                principal.workUnitCode(), status, rows.size(), pending, failed,
                retryOf, now, now, results);
        JobSnapshot snapshot = new JobSnapshot(view, idempotencyKey, contentSha, rows);
        audit.record(principal, JOB_TYPE, jobId.toString(), JOB_COMPLETED, now, json(snapshot));
        return view;
    }

    private void validateGroup(
            List<SamplePointCoordinateCorrectionWorkbook.Row> group, Map<UUID, String> errors) {
        long keepCount = group.stream().filter(row ->
                SamplePointCoordinateCorrectionWorkbook.KEEP.equals(row.action())).count();
        boolean invalidAction = group.stream().anyMatch(row ->
                !(SamplePointCoordinateCorrectionWorkbook.KEEP.equals(row.action())
                        || SamplePointCoordinateCorrectionWorkbook.CHANGE.equals(row.action())));
        if (invalidAction || keepCount != 1) {
            group.forEach(row -> errors.put(row.samplePointId(),
                    "每个重复坐标组必须且只能保留一个原坐标"));
            return;
        }
        group.stream().filter(row -> SamplePointCoordinateCorrectionWorkbook.CHANGE.equals(row.action()))
                .forEach(row -> {
                    if (row.correctedLongitude() == null || row.correctedLatitude() == null
                            || row.coordinateSource().isBlank() || row.correctionNote().isBlank()) {
                        errors.put(row.samplePointId(), "修正坐标、坐标来源和修正说明均为必填");
                    } else if (row.correctedLongitude().compareTo(BigDecimal.valueOf(-180)) < 0
                            || row.correctedLongitude().compareTo(BigDecimal.valueOf(180)) > 0
                            || row.correctedLatitude().compareTo(BigDecimal.valueOf(-90)) < 0
                            || row.correctedLatitude().compareTo(BigDecimal.valueOf(90)) > 0) {
                        errors.put(row.samplePointId(), "经纬度超出合法数值范围");
                    }
                });
    }

    private static void validateFinalCoordinates(
            List<SamplePointCoordinateCorrectionWorkbook.Row> rows, Map<UUID, String> errors) {
        Map<String, List<UUID>> positions = new HashMap<>();
        for (var row : rows) {
            BigDecimal longitude = SamplePointCoordinateCorrectionWorkbook.KEEP.equals(row.action())
                    ? row.originalLongitude() : row.correctedLongitude();
            BigDecimal latitude = SamplePointCoordinateCorrectionWorkbook.KEEP.equals(row.action())
                    ? row.originalLatitude() : row.correctedLatitude();
            if (longitude != null && latitude != null) {
                positions.computeIfAbsent(key(longitude, latitude), ignored -> new ArrayList<>())
                        .add(row.samplePointId());
            }
        }
        positions.values().stream().filter(ids -> ids.size() > 1).flatMap(List::stream)
                .forEach(id -> errors.putIfAbsent(id, "修正后的样本点坐标仍然重复"));
    }

    private static void propagateGroupErrors(
            Map<String, List<SamplePointCoordinateCorrectionWorkbook.Row>> groups,
            Map<UUID, String> errors) {
        groups.values().forEach(group -> {
            String groupError = group.stream().map(row -> errors.get(row.samplePointId()))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (groupError != null) group.forEach(row -> errors.putIfAbsent(row.samplePointId(),
                    "同组存在未通过校验的样本点：" + groupError));
        });
    }

    private static void requireImmutableSnapshot(
            List<SamplePointCoordinateCorrectionWorkbook.Row> expected,
            List<SamplePointCoordinateCorrectionWorkbook.Row> actual) {
        Map<UUID, SamplePointCoordinateCorrectionWorkbook.Row> expectedById = expected.stream()
                .collect(Collectors.toMap(SamplePointCoordinateCorrectionWorkbook.Row::samplePointId,
                        value -> value));
        if (actual.size() != expected.size()
                || !expectedById.keySet().equals(actual.stream().map(
                        SamplePointCoordinateCorrectionWorkbook.Row::samplePointId).collect(Collectors.toSet()))) {
            throw invalidWorkbook();
        }
        for (var row : actual) {
            var source = expectedById.get(row.samplePointId());
            if (source.expectedVersion() != row.expectedVersion()
                    || !source.canonicalName().equals(row.canonicalName())
                    || !source.regionCode().equals(row.regionCode())
                    || !source.regionName().equals(row.regionName())
                    || !source.kindCode().equals(row.kindCode())
                    || source.originalLongitude().compareTo(row.originalLongitude()) != 0
                    || source.originalLatitude().compareTo(row.originalLatitude()) != 0
                    || !source.duplicateGroupId().equals(row.duplicateGroupId())
                    || !source.rowBinding().equals(row.rowBinding())) {
                throw invalidWorkbook();
            }
        }
    }

    private SamplePointCoordinateCorrectionWorkbook.Row exportRow(UUID batchId, Candidate candidate) {
        String group = digest(("GROUP|" + key(candidate.longitude(), candidate.latitude()))
                .getBytes(StandardCharsets.UTF_8));
        String binding = digest((batchId + "|" + candidate.samplePointId() + "|"
                + candidate.version() + "|" + key(candidate.longitude(), candidate.latitude()))
                .getBytes(StandardCharsets.UTF_8));
        return new SamplePointCoordinateCorrectionWorkbook.Row(
                candidate.samplePointId(), candidate.version(), candidate.canonicalName(),
                candidate.regionCode(), candidate.regionName(), candidate.kindCode(),
                candidate.longitude(), candidate.latitude(), group, binding,
                "", null, null, "", "");
    }

    private JobSnapshot ownedJob(UUID jobId, SecurityPrincipal principal) {
        return repository.findJob(jobId, principal.subjectId(), principal.workUnitCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SAMPLE_POINT_CORRECTION_JOB_NOT_FOUND", "坐标修正任务不存在"));
    }

    private static ReviewView reviewView(
            RequestSnapshot request, String status, String reviewer, String reason, Instant reviewedAt) {
        return new ReviewView(request.requestId(), request.samplePointId(), request.expectedVersion(),
                request.canonicalName(), request.regionCode(), request.regionName(),
                request.originalLongitude(), request.originalLatitude(),
                request.correctedLongitude(), request.correctedLatitude(),
                request.coordinateSource(), request.correctionNote(), request.requestedBy(),
                request.createdAt(), request.coordinateCollectedAt(), request.verifiedAddress(),
                request.changeReason(), request.evidenceReference(), status, reviewer, reason,
                reviewedAt);
    }

    private void validateDirectRequest(
            String idempotencyKey, FormalSampleCoordinateChangeCommand command) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() < 8 || idempotencyKey.length() > 128
                || command == null || command.samplePointId() == null
                || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.originalLongitude() == null || command.originalLatitude() == null
                || command.correctedLongitude() == null || command.correctedLatitude() == null
                || blankOrLong(command.coordinateSource(), 40)
                || !COORDINATE_SOURCES.contains(command.coordinateSource().trim())
                || command.coordinateCollectedAt() == null
                || command.coordinateCollectedAt().isAfter(clock.instant())
                || blankOrLong(command.verifiedAddress(), 300)
                || blankOrLong(command.changeReason(), 500)
                || blankOrLong(command.evidenceReference(), 500)) {
            throw invalidCoordinate();
        }
        validateCoordinate(command.originalLongitude(), command.originalLatitude());
        validateCoordinate(command.correctedLongitude(), command.correctedLatitude());
        if (command.correctedLongitude().compareTo(BigDecimal.ZERO) == 0
                && command.correctedLatitude().compareTo(BigDecimal.ZERO) == 0) {
            throw new ClientRequestException("SAMPLE_POINT_COORDINATE_PLACEHOLDER",
                    "经纬度不能使用 0，0 占位坐标");
        }
    }

    private static void validateCoordinate(BigDecimal longitude, BigDecimal latitude) {
        if (longitude.scale() > 7 || latitude.scale() > 7
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw invalidCoordinate();
        }
    }

    private static boolean blankOrLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.trim().length() > maxLength;
    }

    private static String canonicalRequest(FormalSampleCoordinateChangeCommand command) {
        return String.join("|", command.samplePointId().toString(),
                Long.toString(command.expectedVersion()),
                command.originalLongitude().toPlainString(),
                command.originalLatitude().toPlainString(),
                command.correctedLongitude().toPlainString(),
                command.correctedLatitude().toPlainString(),
                command.coordinateSource().trim(), command.coordinateCollectedAt().toString(),
                command.verifiedAddress().trim(), command.changeReason().trim(),
                command.evidenceReference().trim());
    }

    private static ClientRequestException invalidCoordinate() {
        return new ClientRequestException("INVALID_SAMPLE_POINT_COORDINATE",
                "经纬度、来源、采集时间、地址、变更原因或证据不符合要求");
    }

    private void validateUpload(String key, String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128 || filename == null
                || filename.isBlank() || filename.length() > 255
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalidUpload();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SAMPLE_POINT_CORRECTION_AUDIT_SERIALIZATION_FAILED", exception);
        }
    }

    private static String key(BigDecimal longitude, BigDecimal latitude) {
        return longitude.stripTrailingZeros().toPlainString() + "|"
                + latitude.stripTrailingZeros().toPlainString();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static ClientRequestException invalidUpload() {
        return new ClientRequestException("INVALID_SAMPLE_POINT_CORRECTION_UPLOAD",
                "样本点坐标修正文件上传请求无效");
    }

    private static ClientRequestException invalidWorkbook() {
        return new ClientRequestException("INVALID_SAMPLE_POINT_CORRECTION_WORKBOOK",
                "修正表的只读字段、行绑定或样本点范围已被修改");
    }

    public record ErrorFile(String filename, byte[] bytes) {
        public ErrorFile { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
