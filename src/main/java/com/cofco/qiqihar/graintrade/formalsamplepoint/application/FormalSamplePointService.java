package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.ServerContractException;
import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FormalSamplePointService {
    private static final String AGGREGATE = "FORMAL_SAMPLE_POINT";
    private final FormalSamplePointRepository repository;
    private final AccessControl access;
    private final SamplePointCoordinateGuard coordinateGuard;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    public FormalSamplePointService(
            FormalSamplePointRepository repository,
            AccessControl access,
            SamplePointCoordinateGuard coordinateGuard,
            BusinessAuditRecorder audit,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.coordinateGuard = coordinateGuard;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<FormalSamplePointView> list(
            String regionCode, String keyword, int pageNumber, int pageSize) {
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw invalid();
        try {
            Math.multiplyExact((long) pageNumber, pageSize);
        } catch (ArithmeticException exception) {
            throw invalid();
        }
        String region = optional(regionCode, 12);
        String search = optional(keyword, 200);
        AuthorizedReadScope scope = access.requireReadScope();
        if (region != null) scope.requireRegion(region);
        return repository.findPage(
                region, search, pageNumber, pageSize, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public FormalSamplePointView get(UUID id) {
        AuthorizedReadScope scope = access.requireReadScope();
        FormalSamplePointView point = required(id);
        scope.requireRegion(point.regionCode());
        return point;
    }

    @Transactional
    public FormalSamplePointView create(FormalSamplePointDraft submitted) {
        SecurityPrincipal actor = access.require("FORMAL_SAMPLE_MANAGE", null);
        FormalSamplePointDraft draft = normalize(submitted);
        access.require("FORMAL_SAMPLE_MANAGE", draft.regionCode());
        requireValidReferences(draft);
        coordinateGuard.lockAndRequireAvailable(null, draft.longitude(), draft.latitude());
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        FormalSamplePointView created;
        try {
            created = repository.insert(
                            id, draft, actor.subjectId(), LocalDate.now(clock), now)
                    .orElseThrow(FormalSamplePointService::writeLost);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        record(actor, created, "FORMAL_SAMPLE_POINT_CREATED", now,
                List.of(created.regionCode()));
        return created;
    }

    @Transactional
    public FormalSamplePointView update(
            UUID id, long expectedVersion, FormalSamplePointDraft submitted) {
        if (expectedVersion < 0) throw invalid();
        SecurityPrincipal actor = access.require("FORMAL_SAMPLE_MANAGE", null);
        FormalSamplePointView current = required(id);
        access.require("FORMAL_SAMPLE_MANAGE", current.regionCode());
        FormalSamplePointDraft draft = normalize(submitted);
        access.require("FORMAL_SAMPLE_MANAGE", draft.regionCode());
        requireValidReferences(draft);
        coordinateGuard.lockAndRequireAvailable(id, draft.longitude(), draft.latitude());
        Instant now = clock.instant();
        FormalSamplePointView updated;
        try {
            updated = repository.update(
                            id, expectedVersion, draft, actor.subjectId(), now)
                    .orElseThrow(() -> new ConflictException(
                            "FORMAL_SAMPLE_POINT_VERSION_CONFLICT",
                            "正式样本已发生变化，请刷新后重试"));
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        regions.add(current.regionCode());
        regions.add(updated.regionCode());
        record(actor, updated, "FORMAL_SAMPLE_POINT_UPDATED", now,
                List.copyOf(regions));
        return updated;
    }

    @Transactional
    public void delete(UUID id, long expectedVersion) {
        if (expectedVersion < 0) throw invalid();
        FormalSamplePointView point = required(id);
        SecurityPrincipal actor = access.require(
                "FORMAL_SAMPLE_DELETE", point.regionCode());
        switch (repository.delete(
                id, expectedVersion, point.regionCode(), actor.subjectId())) {
            case DELETED -> { }
            case NOT_FOUND -> throw notFound();
            case VERSION_CONFLICT -> throw new ConflictException(
                    "FORMAL_SAMPLE_POINT_VERSION_CONFLICT",
                    "正式样本已发生变化，请刷新后重试");
            case REGION_CONFLICT -> throw new ConflictException(
                    "FORMAL_SAMPLE_POINT_REGION_CONFLICT",
                    "正式样本地区已发生变化，请刷新后重试");
            case ACCESS_DENIED -> throw new AccessDeniedException(
                    "ACCESS_PERMISSION_DENIED", "Operation permission is denied");
            case ACCESS_REGION_DENIED -> throw new AccessDeniedException(
                    "ACCESS_REGION_DENIED", "Data region is outside the assigned scope");
            case NETWORK_REFERENCED -> throw new ConflictException(
                    "FORMAL_SAMPLE_POINT_NETWORK_REFERENCED",
                    "正式样本仍属于年度样本网，不能删除");
            case HISTORICAL_REFERENCE -> throw new ConflictException(
                    "FORMAL_SAMPLE_POINT_HISTORY_REFERENCED",
                    "正式样本仍有关联的供需、导入或证据历史，不能删除");
        }
    }

    private FormalSamplePointView required(UUID id) {
        return repository.find(id).orElseThrow(FormalSamplePointService::notFound);
    }

    private FormalSamplePointDraft normalize(FormalSamplePointDraft submitted) {
        if (submitted == null || submitted.longitude() == null || submitted.latitude() == null) {
            throw invalid();
        }
        String name = required(submitted.canonicalName(), 200);
        String region = required(submitted.regionCode(), 12);
        String address = required(submitted.address(), 500);
        String objectType = required(submitted.objectTypeCode(), 80)
                .toUpperCase(Locale.ROOT);
        BigDecimal longitude = coordinate(submitted.longitude(), new BigDecimal("-180"),
                new BigDecimal("180"), 10);
        BigDecimal latitude = coordinate(submitted.latitude(), new BigDecimal("-90"),
                new BigDecimal("90"), 9);
        return new FormalSamplePointDraft(
                name, region, address, longitude, latitude, objectType);
    }

    private void requireValidReferences(FormalSamplePointDraft draft) {
        if (!repository.isSupportedObjectType(draft.objectTypeCode())) throw invalid();
        FormalSamplePointRepository.BoundaryContainment containment = repository
                .coordinateBoundaryState(
                        draft.regionCode(), draft.longitude(), draft.latitude())
                .orElseThrow(FormalSamplePointService::invalid);
        switch (containment) {
            case UNAVAILABLE -> throw new ServiceUnavailableException(
                    "ADMIN_BOUNDARY_UNAVAILABLE", "所选行政区边界数据暂不可用");
            case OUTSIDE -> throw new ClientRequestException(
                    "COORDINATE_OUTSIDE_REGION", "正式样本坐标不在所选行政区范围内");
            case INSIDE -> { }
        }
    }

    private static BigDecimal coordinate(
            BigDecimal value, BigDecimal minimum, BigDecimal maximum, int maxPrecision) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > 7 || normalized.precision() > maxPrecision
                || normalized.compareTo(minimum) < 0 || normalized.compareTo(maximum) > 0) {
            throw invalid();
        }
        return normalized;
    }

    private static String required(String value, int maxLength) {
        if (value == null) throw invalid();
        try {
            BoundedInput.requireText("INVALID_FORMAL_SAMPLE_POINT", value);
        } catch (ClientRequestException exception) {
            throw invalid();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > maxLength) {
            throw invalid();
        }
        return normalized;
    }

    private void record(
            SecurityPrincipal actor, FormalSamplePointView point, String action,
            Instant occurredAt, List<String> regionCodes) {
        try {
            String detail = json.writeValueAsString(new EventDetail(
                    point.regionCode(), regionCodes, point.objectTypeCode(), point.version()));
            audit.record(actor, AGGREGATE, point.id().toString(), action, occurredAt, detail);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize formal sample point event", exception);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Cannot persist formal sample point event", exception);
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(
                "FORMAL_SAMPLE_POINT_NOT_FOUND", "正式样本不存在");
    }

    private static String optional(String value, int maxLength) {
        if (value == null) return null;
        try {
            BoundedInput.requireText("INVALID_FORMAL_SAMPLE_POINT", value);
            String normalized = value.trim();
            if (normalized.isEmpty()
                    || normalized.codePointCount(0, normalized.length()) > maxLength) {
                throw invalid();
            }
            return normalized;
        } catch (ClientRequestException exception) {
            throw invalid();
        }
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_FORMAL_SAMPLE_POINT", "正式样本请求参数无效");
    }

    private static ConflictException conflict() {
        return new ConflictException(
                "FORMAL_SAMPLE_POINT_CONFLICT", "正式样本与现有记录冲突");
    }

    private static ServerContractException writeLost() {
        return new ServerContractException(
                "FORMAL_SAMPLE_POINT_WRITE_LOST", "正式样本写入结果不可重查");
    }

    private record EventDetail(
            String regionCode, List<String> regionCodes, String objectTypeCode, long version) {}

}
