package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FormalSamplePointService {
    private final FormalSamplePointRepository repository;
    private final AccessControl access;

    public FormalSamplePointService(
            FormalSamplePointRepository repository,
            AccessControl access) {
        this.repository = repository;
        this.access = access;
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

}
