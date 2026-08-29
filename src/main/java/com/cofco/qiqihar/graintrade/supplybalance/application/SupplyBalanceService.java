package com.cofco.qiqihar.graintrade.supplybalance.application;

import com.cofco.qiqihar.graintrade.regionalproduction.api.RegionalProductionReferenceQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceCalculator.RegionalProductionSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SupplyBalanceService {
    private static final String AGGREGATE = "SUPPLY_DEMAND_BALANCE";
    private final SupplyBalanceRepository repository;
    private final RegionalProductionReferenceQuery regionalReferences;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SupplyBalanceCalculator calculator = new SupplyBalanceCalculator();

    public SupplyBalanceService(
            SupplyBalanceRepository repository, RegionalProductionReferenceQuery regionalReferences,
            AccessControl access, BusinessAuditRecorder audit, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.regionalReferences = regionalReferences;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SupplyBalanceView find(String regionCode, int surveyYear, String productCode) {
        validateYear(surveyYear);
        String product = normalizedProduct(productCode);
        RegionalProductionReferenceQuery.RegionReference region = requireRegion(regionCode);
        if (!Set.of("COUNTY", "PREFECTURE").contains(region.administrativeLevel())) {
            throw invalid("SUPPLY_BALANCE_REGION_INVALID", "供需平衡仅支持地级市或区县");
        }
        SecurityPrincipal principal = access.require("BUSINESS_READ", regionCode);
        List<SupplyBalanceRepository.CountySource> sources = repository.countySources(
                regionCode, surveyYear, product, principal.regionCodes());
        if (sources.isEmpty()) {
            throw invalid("SUPPLY_BALANCE_SCOPE_EMPTY", "当前辖区没有可读取的区县");
        }
        Aggregate aggregate = "COUNTY".equals(region.administrativeLevel())
                ? county(sources.getFirst()) : prefecture(sources, product);
        return new SupplyBalanceView(
                region.code(), region.name(), region.administrativeLevel(), surveyYear, product,
                aggregate.production() != null, aggregate.version(), aggregate.updatedAt(),
                calculator.calculate(product, aggregate.production(),
                        aggregate.manualValues(), aggregate.notes()));
    }

    @Transactional
    public SupplyBalanceView upsert(
            String regionCode, int surveyYear, String productCode,
            Map<String, BigDecimal> manualValues, Map<String, String> notes, long expectedVersion) {
        validateYear(surveyYear);
        String product = normalizedProduct(productCode);
        SupplyBalanceProductContract contract = SupplyBalanceProductContract.forProduct(product);
        RegionalProductionReferenceQuery.RegionReference region = requireRegion(regionCode);
        if (!"COUNTY".equals(region.administrativeLevel())) {
            throw invalid("SUPPLY_BALANCE_COUNTY_REQUIRED", "供需平衡只能按区县填报");
        }
        Map<String, BigDecimal> values = validateManualValues(manualValues, contract);
        Map<String, String> safeNotes = validateNotes(notes, contract);
        if (expectedVersion < 0) {
            throw invalid("SUPPLY_BALANCE_VERSION_INVALID", "数据版本无效");
        }
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", regionCode);
        Instant now = clock.instant();
        repository.upsert(regionCode, surveyYear, product, values, safeNotes,
                        expectedVersion, principal.subjectId(), now)
                .orElseThrow(() -> new ConflictException(
                        "SUPPLY_BALANCE_VERSION_CONFLICT", "数据已更新，请核对后重试"));
        audit.record(principal, AGGREGATE, aggregateId(regionCode, surveyYear, product),
                "SUPPLY_BALANCE_UPSERTED", now,
                eventDetail(regionCode, region.parentCode(), surveyYear, product, values.keySet()));
        return find(regionCode, surveyYear, product);
    }

    @Transactional(readOnly = true)
    public List<SupplyBalanceRepository.HistoryEntry> history(
            String regionCode, int surveyYear, String productCode) {
        validateYear(surveyYear);
        String product = normalizedProduct(productCode);
        RegionalProductionReferenceQuery.RegionReference region = requireRegion(regionCode);
        if (!"COUNTY".equals(region.administrativeLevel())) {
            throw invalid("SUPPLY_BALANCE_COUNTY_REQUIRED", "历史仅支持区县数据");
        }
        access.require("BUSINESS_READ", regionCode);
        return repository.history(regionCode, surveyYear, product);
    }

    private Aggregate county(SupplyBalanceRepository.CountySource source) {
        return new Aggregate(source.production(), source.manualValues(), source.notes(),
                source.version(), source.updatedAt());
    }

    private Aggregate prefecture(List<SupplyBalanceRepository.CountySource> sources, String productCode) {
        List<SupplyBalanceRepository.CountySource> productionSources = sources.stream()
                .filter(source -> source.production() != null).toList();
        RegionalProductionSource production = null;
        if (!productionSources.isEmpty()) {
            BigDecimal area = productionSources.stream().map(source -> source.production().plantedAreaMu())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean outputAvailable = productionSources.stream()
                    .allMatch(source -> source.production().totalOutputKg() != null);
            BigDecimal output = outputAvailable
                    ? productionSources.stream().map(source -> source.production().totalOutputKg())
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                    : null;
            BigDecimal yield = output == null ? null : area.signum() == 0 ? BigDecimal.ZERO
                    : output.divide(area, 6, RoundingMode.HALF_UP);
            production = new RegionalProductionSource(area, yield, output);
        }
        List<SupplyBalanceRepository.CountySource> balanceSources = sources.stream()
                .filter(SupplyBalanceRepository.CountySource::balancePresent).toList();
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String code : SupplyBalanceProductContract.forProduct(productCode).manualCodes()) {
            if (!balanceSources.isEmpty()
                    && balanceSources.stream().allMatch(source -> source.manualValues().containsKey(code))) {
                values.put(code, balanceSources.stream().map(source -> source.manualValues().get(code))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        }
        Instant updatedAt = sources.stream().map(SupplyBalanceRepository.CountySource::updatedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        return new Aggregate(production, Map.copyOf(values), Map.of(), 0, updatedAt);
    }

    private Map<String, BigDecimal> validateManualValues(
            Map<String, BigDecimal> values, SupplyBalanceProductContract contract) {
        if (values == null || !contract.manualCodes().containsAll(values.keySet())) {
            throw invalid("SUPPLY_BALANCE_FIELD_INVALID", "包含当前品种不允许的供需字段");
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        values.forEach((code, value) -> {
            if (value == null || value.signum() < 0 || value.scale() > 4 || value.precision() > 18) {
                throw invalid("SUPPLY_BALANCE_VALUE_INVALID", "人工供需值必须为非负且最多保留4位小数");
            }
            normalized.put(code, value.setScale(4));
        });
        return Map.copyOf(normalized);
    }

    private Map<String, String> validateNotes(
            Map<String, String> notes, SupplyBalanceProductContract contract) {
        if (notes == null || !contract.allCodes().containsAll(notes.keySet())) {
            throw invalid("SUPPLY_BALANCE_NOTE_INVALID", "包含当前品种不允许的说明字段");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        notes.forEach((code, note) -> {
            if (note == null || note.length() > 500) {
                throw invalid("SUPPLY_BALANCE_NOTE_INVALID", "单项必要说明不得超过500字");
            }
            if (!note.isBlank()) normalized.put(code, note.trim());
        });
        return Map.copyOf(normalized);
    }

    private String normalizedProduct(String productCode) {
        String product = productCode == null ? null : productCode.trim().toUpperCase();
        SupplyBalanceProductContract.forProduct(product);
        if (!regionalReferences.supportsProduct(product)) {
            throw invalid("SUPPLY_BALANCE_PRODUCT_INVALID", "供需平衡品种无效");
        }
        return product;
    }

    private RegionalProductionReferenceQuery.RegionReference requireRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            throw invalid("SUPPLY_BALANCE_REGION_INVALID", "地区代码不能为空");
        }
        return regionalReferences.findRegion(regionCode.trim()).orElseThrow(() ->
                invalid("SUPPLY_BALANCE_REGION_INVALID", "地区代码不存在"));
    }

    private static void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw invalid("SUPPLY_BALANCE_YEAR_INVALID", "供需年度必须在2000至2100之间");
        }
    }

    private String eventDetail(
            String regionCode, String prefectureCode, int year, String productCode, Set<String> fields) {
        try {
            return objectMapper.writeValueAsString(new EventDetail(regionCode,
                    prefectureCode == null ? List.of(regionCode) : List.of(regionCode, prefectureCode),
                    year, productCode, fields.stream().sorted().toList()));
        } catch (Exception exception) {
            throw new IllegalStateException("Supply balance event cannot be serialized", exception);
        }
    }

    private static String aggregateId(String regionCode, int year, String productCode) {
        return regionCode + ":" + year + ":" + productCode;
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }

    private record Aggregate(
            RegionalProductionSource production, Map<String, BigDecimal> manualValues,
            Map<String, String> notes, long version, Instant updatedAt) {}
    private record EventDetail(
            String regionCode, List<String> regionCodes, int surveyYear,
            String productCode, List<String> changedFields) {}
}
