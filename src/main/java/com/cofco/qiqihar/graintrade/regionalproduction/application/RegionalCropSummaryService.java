package com.cofco.qiqihar.graintrade.regionalproduction.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegionalCropSummaryService {
    private static final Set<String> PRODUCTS = Set.of("CORN", "SOYBEAN", "RICE");
    private final RegionalCropSummaryRepository summaries;
    private final RegionalCropAnnualStatRepository annualStats;
    private final AccessControl access;

    public RegionalCropSummaryService(
            RegionalCropSummaryRepository summaries,
            RegionalCropAnnualStatRepository annualStats,
            AccessControl access) {
        this.summaries = summaries;
        this.annualStats = annualStats;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public RegionalCropSummary summarize(int year, String productCode, String regionCode) {
        if (year < 2001 || year > 2100) {
            throw invalid("REGIONAL_CROP_SUMMARY_YEAR_INVALID", "汇总年度必须在2001至2100之间");
        }
        String product = productCode == null ? null : productCode.trim().toUpperCase();
        if (product == null || !PRODUCTS.contains(product) || !annualStats.knownProduct(product)) {
            throw invalid("REGIONAL_CROP_SUMMARY_PRODUCT_INVALID", "品种代码不存在或不支持");
        }
        RegionalCropAnnualStatRepository.RegionDescriptor region = annualStats.region(regionCode)
                .orElseThrow(() -> invalid("REGIONAL_CROP_SUMMARY_REGION_INVALID", "地区代码不存在"));
        if (!Set.of("COUNTY", "PREFECTURE").contains(region.administrativeLevel())) {
            throw invalid("REGIONAL_CROP_SUMMARY_REGION_INVALID", "地区汇总仅支持地级市或区县");
        }
        SecurityPrincipal principal = access.require("BUSINESS_READ", regionCode);
        return summaries.summarize(year, product, regionCode, principal.regionCodes())
                .orElseThrow(() -> invalid("REGIONAL_CROP_SUMMARY_SCOPE_EMPTY", "当前辖区没有可汇总的区县"));
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }
}
