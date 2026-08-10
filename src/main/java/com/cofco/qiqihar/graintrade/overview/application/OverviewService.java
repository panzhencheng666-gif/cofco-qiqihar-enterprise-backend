package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OverviewService {
    private final OverviewRepository repository;
    private final AccessControl accessControl;
    public OverviewService(OverviewRepository repository) { this(repository, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public OverviewService(OverviewRepository repository, AccessControl accessControl) {
        this.repository = repository; this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public OverviewOptions options() { return repository.options(); }

    @Transactional(readOnly = true)
    public OverviewMapScope mapScope() { return repository.mapScope(); }

    @Transactional(readOnly = true)
    public List<OverviewRegion> regions(String parentCode, String productCode, String periodCode) {
        if (blank(productCode) || !repository.knownProduct(productCode)
                || (!blank(periodCode) && !repository.knownPeriod(periodCode))
                || (parentCode != null && !repository.knownRegion(parentCode))) throw invalid();
        AuthorizedReadScope scope=readScope();
        if(scope.regionCodes().isEmpty())return List.of();
        if(!blank(parentCode)&&!repository.canNavigateRegion(parentCode,scope.regionCodes()))scope.requireRegion(parentCode);
        return repository.regions(parentCode, productCode, periodCode, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public List<OverviewRegion> locations(String ancestorCode, String level, String productCode, String periodCode) {
        if (blank(productCode) || !repository.knownProduct(productCode)
                || (!blank(periodCode) && !repository.knownPeriod(periodCode))
                || (!blank(ancestorCode) && !repository.knownRegion(ancestorCode))
                || !("TOWNSHIP".equals(level) || "VILLAGE".equals(level))) throw invalid();
        AuthorizedReadScope scope=readScope();
        if(scope.regionCodes().isEmpty())return List.of();
        if(!blank(ancestorCode)&&!repository.canNavigateRegion(ancestorCode,scope.regionCodes()))scope.requireRegion(ancestorCode);
        return repository.locations(ancestorCode, level, productCode, periodCode, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public List<OverviewIndicator> indicators(String productCode, String regionCode, String periodCode, String marketingYear) {
        if (blank(productCode) || blank(regionCode) || blank(periodCode)
                || !repository.knownProduct(productCode) || !repository.knownRegion(regionCode)
                || !repository.knownPeriod(periodCode)) throw invalid();
        AuthorizedReadScope scope=readScope(); scope.requireRegion(regionCode);
        return repository.indicators(productCode, regionCode, periodCode, marketingYear, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public OverviewDashboard dashboard(
            String productCode, String periodCode, String regionCode, String marketingYear) {
        if (blank(productCode) || !repository.knownProduct(productCode)
                || (!blank(periodCode) && !repository.knownPeriod(periodCode))
                || (!blank(regionCode) && !repository.knownRegion(regionCode))) throw invalid();
        AuthorizedReadScope scope=readScope();
        if(scope.regionCodes().isEmpty())scope.requireRegion(regionCode);
        if(!blank(regionCode))scope.requireRegion(regionCode);
        return repository.dashboard(productCode, periodCode, regionCode, marketingYear, scope.regionCodes());
    }

    @Transactional(readOnly = true)
    public List<AnnualComparisonDefinition> annualComparisonDefinitions(String sourceDomain, String productCode) {
        if (!("PRODUCTION".equals(sourceDomain) || "MARKET".equals(sourceDomain))
                || blank(productCode) || !repository.knownProduct(productCode)) throw invalid();
        if (readScope().regionCodes().isEmpty()) return List.of();
        return repository.annualComparisonDefinitions(sourceDomain, productCode);
    }

    @Transactional(readOnly = true)
    public AnnualComparisonView annualComparison(String productCode, String cultivarCode, String regionCode,
            Integer surveyYear, String periodCode, String indicatorCode) {
        if (blank(productCode) || blank(regionCode) || blank(indicatorCode)
                || !repository.knownProduct(productCode) || !repository.knownRegion(regionCode)
                || (!blank(cultivarCode) && !repository.knownCultivar(productCode, cultivarCode))) throw invalid();
        Integer effectiveSurveyYear = surveyYear;
        if (effectiveSurveyYear == null && !blank(periodCode)) {
            effectiveSurveyYear = repository.surveyYearForPeriod(periodCode).orElseThrow(OverviewService::invalid);
        }
        if (effectiveSurveyYear == null || effectiveSurveyYear < 1900 || effectiveSurveyYear > 2200) throw invalid();
        AnnualComparisonDefinition definition = repository.annualComparisonDefinition(indicatorCode)
                .orElseThrow(OverviewService::invalid);
        if (!("PRODUCTION".equals(definition.sourceDomain()) || "MARKET".equals(definition.sourceDomain()))
                || ("MARKET".equals(definition.sourceDomain()) && !blank(cultivarCode))) throw invalid();
        AuthorizedReadScope scope = readScope();
        scope.requireRegion(regionCode);
        return new AnnualComparisonView(definition.code(), definition.name(), definition.sourceDomain(), productCode,
                cultivarCode, regionCode, effectiveSurveyYear, surveyYear == null ? periodCode : null,
                definition.unitCode(), "OVERVIEW_APPROVED_FACTS_V3_SURVEY_YEAR",
                repository.annualComparison(productCode, cultivarCode, regionCode, effectiveSurveyYear, definition,
                        scope.regionCodes()));
    }

    private AuthorizedReadScope readScope(){return accessControl==null?AuthorizedReadScope.unrestricted():accessControl.requireReadScope();}

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_OVERVIEW_QUERY", "Overview query context is invalid"); }
}
