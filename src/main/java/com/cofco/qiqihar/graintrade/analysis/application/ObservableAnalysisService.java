package com.cofco.qiqihar.graintrade.analysis.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObservableAnalysisService {
    private final ObservableAnalysisRepository repository;
    private final AccessControl accessControl;

    public ObservableAnalysisService(ObservableAnalysisRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ObservableAnalysisService(
            ObservableAnalysisRepository repository, AccessControl accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public ObservableAnalysisSnapshot snapshot(
            String productCode,
            String regionCode,
            Integer surveyYear,
            Integer surveyMonth,
            String cultivarCode,
            String subjectTypeCode) {
        validate(productCode, regionCode, surveyYear, surveyMonth, cultivarCode, subjectTypeCode);
        ObservableAnalysisScope requested = new ObservableAnalysisScope(
                productCode, regionCode, surveyYear, surveyMonth, cultivarCode, subjectTypeCode);
        AuthorizedReadScope readScope = readScope();
        if (!requested.isAllAuthorizedRegions()
                && !repository.canNavigateRegion(regionCode, readScope.regionCodes())) {
            readScope.requireRegion(regionCode);
        }
        return repository.load(requested, readScope.regionCodes());
    }

    private void validate(
            String productCode,
            String regionCode,
            Integer surveyYear,
            Integer surveyMonth,
            String cultivarCode,
            String subjectTypeCode) {
        if (blank(productCode) || blank(regionCode) || surveyYear == null
                || surveyYear < 1900 || surveyYear > 2200
                || (surveyMonth != null && (surveyMonth < 1 || surveyMonth > 12))
                || !repository.knownProduct(productCode)
                || (!ObservableAnalysisScope.ALL_AUTHORIZED_REGIONS.equals(regionCode)
                    && !repository.knownRegion(regionCode))
                || (!blank(cultivarCode) && !repository.knownCultivar(productCode, cultivarCode))
                || (!blank(subjectTypeCode)
                    && !repository.knownSubjectType("PRODUCTION", subjectTypeCode)
                    && !repository.knownSubjectType("MARKET", subjectTypeCode))) {
            throw invalid();
        }
    }

    private AuthorizedReadScope readScope() {
        return accessControl == null
                ? AuthorizedReadScope.unrestricted()
                : accessControl.requireReadScope();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_OBSERVABLE_ANALYSIS_QUERY", "Observable analysis query context is invalid");
    }
}
