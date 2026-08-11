package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OverviewSamplePointService {
    private static final int MAX_QUERY_LENGTH = 120;

    private final OverviewSamplePointRepository samplePoints;
    private final OverviewRepository overview;
    private final AccessControl accessControl;

    public OverviewSamplePointService(OverviewSamplePointRepository samplePoints,
            OverviewRepository overview, AccessControl accessControl) {
        this.samplePoints = samplePoints;
        this.overview = overview;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public List<OverviewSamplePointAggregate> aggregates(String productCode, String parentCode) {
        validateProduct(productCode);
        if (!blank(parentCode) && !overview.knownRegion(parentCode)) throw invalid();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        if (scope.regionCodes().isEmpty()) return List.of();
        authorizeNavigation(parentCode, scope);
        return samplePoints.aggregates(productCode, parentCode, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public OverviewSamplePointList list(String productCode, String regionCode, String categoryCode, String typeCode, String query) {
        validateFilter(productCode, regionCode, categoryCode, typeCode);
        String normalizedQuery = normalizeQuery(query);
        AuthorizedReadScope scope = accessControl.requireReadScope();
        authorizeNavigation(regionCode, scope);
        return samplePoints.list(productCode, regionCode, categoryCode, typeCode, normalizedQuery, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public List<OverviewSamplePointIcon> icons(String productCode, String regionCode, String categoryCode, String typeCode) {
        validateFilter(productCode, regionCode, categoryCode, typeCode);
        if (blank(categoryCode) || !"VILLAGE".equals(samplePoints.regionLevel(regionCode))) throw invalid();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        scope.requireRegion(regionCode);
        return samplePoints.icons(productCode, regionCode, categoryCode, typeCode, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public OverviewSamplePointDetail detail(String productCode, UUID samplePointId, String regionCode) {
        validateProduct(productCode);
        if (samplePointId == null || blank(regionCode) || !overview.knownRegion(regionCode)) throw invalid();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        authorizeNavigation(regionCode, scope);
        return samplePoints.detail(productCode, samplePointId, regionCode, scope.regionCodes())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OVERVIEW_SAMPLE_POINT_NOT_FOUND", "Sample point was not found"));
    }

    private void validateFilter(String productCode, String regionCode, String categoryCode, String typeCode) {
        validateProduct(productCode);
        if (blank(regionCode) || !overview.knownRegion(regionCode)) throw invalid();
        if (!blank(categoryCode) && !samplePoints.knownCategory(categoryCode)) throw invalid();
        if (!blank(typeCode) && (blank(categoryCode)
                || !samplePoints.knownType(productCode, categoryCode, typeCode))) {
            throw invalid();
        }
    }

    private void validateProduct(String productCode) {
        if (blank(productCode) || !overview.knownProduct(productCode)) throw invalid();
    }

    private void authorizeNavigation(String regionCode, AuthorizedReadScope scope) {
        if (blank(regionCode)) return;
        if (!overview.canNavigateRegion(regionCode, scope.regionCodes())) scope.requireRegion(regionCode);
    }

    private static String normalizeQuery(String query) {
        if (blank(query)) return null;
        String normalized = query.trim();
        if (normalized.length() > MAX_QUERY_LENGTH) throw invalid();
        return normalized;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_OVERVIEW_SAMPLE_POINT_QUERY", "Overview sample-point query is invalid");
    }
}
