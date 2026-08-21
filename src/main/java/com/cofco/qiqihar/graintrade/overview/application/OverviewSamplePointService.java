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
    public List<OverviewSamplePointAggregate> aggregates(Integer year, String productCode, String parentCode) {
        int effectiveYear = effectiveYear(year);
        validateProduct(productCode);
        if (!blank(parentCode) && (!overview.knownRegion(parentCode)
                || !"PREFECTURE".equals(samplePoints.regionLevel(parentCode)))) throw invalid();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        if (scope.regionCodes().isEmpty()) return List.of();
        authorizeNavigation(parentCode, scope);
        return samplePoints.aggregates(effectiveYear, productCode, parentCode, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public OverviewSamplePointList list(Integer year, String productCode, String regionCode,
            String categoryCode, String typeCode, String query) {
        int effectiveYear = effectiveYear(year);
        validateProduct(productCode);
        validateFilter(regionCode, categoryCode, typeCode);
        String normalizedQuery = normalizeQuery(query);
        AuthorizedReadScope scope = accessControl.requireReadScope();
        authorizeNavigation(regionCode, scope);
        return samplePoints.list(effectiveYear, productCode, regionCode, categoryCode, typeCode,
                normalizedQuery, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public List<OverviewSamplePointIcon> icons(Integer year, String productCode, String regionCode,
            String categoryCode,
            String typeCode, String query) {
        int effectiveYear = effectiveYear(year);
        validateProduct(productCode);
        validateFilter(regionCode, categoryCode, typeCode);
        if (blank(categoryCode) || !iconRegionLevel(samplePoints.regionLevel(regionCode))) throw invalid();
        String normalizedQuery = normalizeQuery(query);
        AuthorizedReadScope scope = accessControl.requireReadScope();
        authorizeNavigation(regionCode, scope);
        return samplePoints.icons(effectiveYear, productCode, regionCode, categoryCode, typeCode, normalizedQuery,
                scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public OverviewSamplePointDetail detail(Integer year, String productCode, UUID samplePointId, String regionCode,
            String categoryCode, String typeCode) {
        int effectiveYear = effectiveYear(year);
        validateProduct(productCode);
        validateFilter(regionCode, categoryCode, typeCode);
        if (samplePointId == null) throw invalid();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        authorizeNavigation(regionCode, scope);
        return samplePoints.detail(effectiveYear, productCode, samplePointId, regionCode, categoryCode, typeCode,
                        scope.regionCodes())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OVERVIEW_SAMPLE_POINT_NOT_FOUND", "Sample point was not found"));
    }

    private void validateFilter(String regionCode, String categoryCode, String typeCode) {
        if (blank(regionCode) || !overview.knownRegion(regionCode)) throw invalid();
        if (!blank(categoryCode) && !samplePoints.knownCategory(categoryCode)) throw invalid();
        if (!blank(typeCode) && (blank(categoryCode)
                || !samplePoints.knownType(categoryCode, typeCode))) {
            throw invalid();
        }
    }

    private void validateProduct(String productCode) {
        if (blank(productCode) || !overview.knownProduct(productCode)) throw invalid();
    }

    private int effectiveYear(Integer year) {
        if (year == null || year < 1900 || year > 2200) throw invalid();
        return year;
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

    private static boolean iconRegionLevel(String level) {
        return "PREFECTURE".equals(level) || "COUNTY".equals(level)
                || "TOWNSHIP".equals(level) || "VILLAGE".equals(level);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_OVERVIEW_SAMPLE_POINT_QUERY", "Overview sample-point query is invalid");
    }
}
