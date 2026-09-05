package com.cofco.qiqihar.graintrade.regionalproduction.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RegionalCropAnnualStatService {
    private static final String AGGREGATE = "REGIONAL_CROP_ANNUAL_STAT";
    private static final String ACTION = "REGIONAL_CROP_ANNUAL_STAT_UPSERTED";
    private static final Set<String> SUPPORTED_PRODUCTS = Set.of("CORN", "SOYBEAN", "RICE");

    private final RegionalCropAnnualStatRepository repository;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RegionalCropAnnualStatService(
            RegionalCropAnnualStatRepository repository,
            AccessControl access,
            BusinessAuditRecorder audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RegionalCropAnnualStat> findAll(int year, String productCode, String prefectureCode) {
        validateYear(year);
        String product = validateProduct(productCode);
        RegionalCropAnnualStatRepository.RegionDescriptor prefecture = requireRegion(prefectureCode);
        if (!"PREFECTURE".equals(prefecture.administrativeLevel())) {
            throw invalid("REGIONAL_ANNUAL_STAT_PREFECTURE_INVALID", "查询地区必须为地级市");
        }
        SecurityPrincipal principal = access.require("BUSINESS_READ", null);
        return repository.findAll(year, product, prefectureCode, principal.regionCodes());
    }

    @Transactional
    public RegionalCropAnnualStat upsert(
            String regionCode, int year, String productCode,
            BigDecimal plantedAreaMu, BigDecimal yieldPerMuKg, long expectedVersion) {
        validateYear(year);
        String product = validateProduct(productCode);
        RegionalCropAnnualStatRepository.RegionDescriptor region = requireRegion(regionCode);
        if (!"COUNTY".equals(region.administrativeLevel())) {
            throw invalid("REGIONAL_ANNUAL_STAT_COUNTY_REQUIRED", "地区年度产情只能按区县填报");
        }
        BigDecimal area = validateDecimal(plantedAreaMu, "plantedAreaMu");
        BigDecimal yield = validateOptionalDecimal(yieldPerMuKg, "yieldPerMuKg");
        if (expectedVersion < 0) {
            throw invalid("REGIONAL_ANNUAL_STAT_VERSION_INVALID", "数据版本无效");
        }
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", regionCode);
        access.requireCountyReporter(principal,regionCode);
        Instant now = clock.instant();
        RegionalCropAnnualStat saved = repository.upsert(
                        regionCode, year, product, area, yield,
                        expectedVersion, principal.subjectId(), now)
                .orElseThrow(() -> new ConflictException(
                        "REGIONAL_ANNUAL_STAT_VERSION_CONFLICT", "数据已更新，请核对后重试"));
        audit.record(principal, AGGREGATE, aggregateId(regionCode, year, product), ACTION, now,
                eventDetail(saved));
        return saved;
    }

    private RegionalCropAnnualStatRepository.RegionDescriptor requireRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            throw invalid("REGIONAL_ANNUAL_STAT_REGION_INVALID", "地区代码不能为空");
        }
        return repository.region(regionCode.trim()).orElseThrow(() ->
                invalid("REGIONAL_ANNUAL_STAT_REGION_INVALID", "地区代码不存在"));
    }

    private String validateProduct(String productCode) {
        String normalized = productCode == null ? null : productCode.trim().toUpperCase();
        if (normalized == null || !SUPPORTED_PRODUCTS.contains(normalized)
                || !repository.knownProduct(normalized)) {
            throw invalid("REGIONAL_ANNUAL_STAT_PRODUCT_INVALID", "品种代码不存在或不支持");
        }
        return normalized;
    }

    private static void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw invalid("REGIONAL_ANNUAL_STAT_YEAR_INVALID", "数据年度必须在2000至2100之间");
        }
    }

    private static BigDecimal validateDecimal(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 4 || value.precision() > 20) {
            throw invalid("REGIONAL_ANNUAL_STAT_VALUE_INVALID",
                    field + "必须为非负且最多保留4位小数");
        }
        return value.setScale(4);
    }

    private static BigDecimal validateOptionalDecimal(BigDecimal value, String field) {
        return value == null ? null : validateDecimal(value, field);
    }

    private String eventDetail(RegionalCropAnnualStat stat) {
        try {
            return objectMapper.writeValueAsString(new EventDetail(
                    stat.regionCode(), List.of(stat.regionCode(), stat.prefectureCode()),
                    stat.dataYear(), stat.productCode(), stat.version()));
        } catch (Exception exception) {
            throw new IllegalStateException("Regional annual stat event cannot be serialized", exception);
        }
    }

    private static String aggregateId(String regionCode, int year, String productCode) {
        return regionCode + ":" + year + ":" + productCode;
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }

    private record EventDetail(
            String regionCode, List<String> regionCodes, int surveyYear, String productCode, long version) {}
}
