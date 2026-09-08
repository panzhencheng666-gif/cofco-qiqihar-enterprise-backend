package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePointReader;
import com.cofco.qiqihar.graintrade.overview.api.CurrentOverviewSamplePoint;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FormalSampleRetirementBatchService {
    private final FormalSampleRetirementBatchRepository batches;
    private final FormalSamplePointRepository points;
    private final FormalSamplePointService lifecycle;
    private final CurrentOverviewSamplePointReader current;
    private final AccessControl access;
    private final Clock clock;

    public FormalSampleRetirementBatchService(FormalSampleRetirementBatchRepository batches,
            FormalSamplePointRepository points, FormalSamplePointService lifecycle,
            CurrentOverviewSamplePointReader current, AccessControl access, Clock clock) {
        this.batches = batches;
        this.points = points;
        this.lifecycle = lifecycle;
        this.current = current;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FormalSampleRetirementBatch preview() {
        var actor = actor();
        LocalDate date = points.retirementDate();
        var batch = new FormalSampleRetirementBatch(UUID.randomUUID(), actor.subjectId(),
                actor.workUnitCode(), actor.regionCodes().stream().sorted().toList(), date,
                clock.instant().plus(Duration.ofMinutes(10)), candidates(actor, date), null, null);
        batches.save(batch);
        return batch;
    }

    @Transactional(readOnly = true)
    public FormalSampleRetirementBatch get(UUID id) {
        return owned(id, actor(), false);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public FormalSampleRetirementBatch execute(UUID id, String submittedReason) {
        var actor = actor();
        var batch = owned(id, actor, true);
        String reason = reason(submittedReason);
        if (batch.retiredCount() != null) {
            if (!reason.equals(batch.reason())) throw new ConflictException(
                    "RETIREMENT_REQUEST_CONFLICT", "该预览已按其他原因执行，请查询原结果");
            return batch;
        }
        LocalDate date = points.retirementDate();
        if (!clock.instant().isBefore(batch.expiresAt()) || !date.equals(batch.businessDate())
                || !batch.workUnitCode().equals(actor.workUnitCode())
                || !batch.authorizedRegions().equals(actor.regionCodes().stream().sorted().toList())
                || !batch.candidates().equals(candidates(actor, date))) throw stale();
        for (var point : batch.candidates()) {
            lifecycle.retire(point.id(), point.version(), reason);
        }
        batches.complete(id, reason, batch.candidateCount());
        return owned(id, actor, false);
    }

    private SecurityPrincipal actor() {
        access.requireReadScope();
        return access.require("FORMAL_SAMPLE_DELETE", null);
    }

    private FormalSampleRetirementBatch owned(UUID id, SecurityPrincipal actor, boolean lock) {
        var batch = batches.find(id, lock).orElseThrow(FormalSampleRetirementBatchService::notFound);
        if (!batch.actorSubjectId().equals(actor.subjectId())
                || !batch.workUnitCode().equals(actor.workUnitCode())
                || !actor.regionCodes().containsAll(batch.candidates().stream()
                        .map(FormalSampleRetirementBatch.Candidate::regionCode).toList())) throw notFound();
        return batch;
    }

    private List<FormalSampleRetirementBatch.Candidate> candidates(SecurityPrincipal actor, LocalDate date) {
        return current.readAtLifecycleCutoff(date.getYear(), null, null, null, date, actor.regionCodes())
                .stream().map(CurrentOverviewSamplePoint::samplePointId).distinct().sorted()
                .map(id -> {
                    var point = points.find(id).orElseThrow(FormalSampleRetirementBatchService::stale);
                    access.require("FORMAL_SAMPLE_DELETE", point.regionCode());
                    return new FormalSampleRetirementBatch.Candidate(
                            id, point.version(), point.regionCode(), point.canonicalName());
                }).toList();
    }

    private static String reason(String value) {
        if (value == null) throw invalid();
        BoundedInput.requireText("INVALID_RETIREMENT_REASON", value);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 500) throw invalid();
        return trimmed;
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException("INVALID_RETIREMENT_REASON", "请填写不超过500字的淘汰原因");
    }
    private static ConflictException stale() {
        return new ConflictException("RETIREMENT_PREVIEW_STALE", "样本集合、版本或预览有效期已变化，请重新预览");
    }
    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("RETIREMENT_PREVIEW_NOT_FOUND", "淘汰预览不存在或不在当前账号权限内");
    }
}
